package com.photoalbum.controller;

import com.photoalbum.common.BusinessException;
import com.photoalbum.common.RateLimiter;
import com.photoalbum.common.Result;
import com.photoalbum.dto.ShareLinkDTO;
import com.photoalbum.security.ClientIpResolver;
import com.photoalbum.service.PhotoService;
import com.photoalbum.service.ShareCardService;
import com.photoalbum.service.ShareService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * 分享访问限流测试（对应安全审查报告 H02）
 *
 * 缺陷回顾：分享访问接口用它校验访问口令，却不限尝试次数。
 * 服务端以 BCrypt 校验，单次仅数十毫秒，不限流时短口令可被高速穷举。
 *
 * 本测试把三条约束固定下来：
 *   1. 同一「分享码 + 来源」连续尝试超过阈值后被限流；
 *   2. 换一个分享码不受影响（限流不作用于其它链接）；
 *   3. 换一个来源地址不受影响（避免把防护做成对正常访客的拒绝服务）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("分享访问限流（H02 修复验证）")
class ShareControllerAccessLimitTest {

    private static final String IP = "203.0.113.9";
    private static final String OTHER_IP = "198.51.100.7";

    /** 与 ShareController 中的 ACCESS_MAX_ATTEMPTS 对应：恰好 5 次放行，第 6 次拒绝 */
    private static final int ALLOWED_ATTEMPTS = 5;

    @Mock
    private ShareService shareService;
    @Mock
    private PhotoService photoService;
    @Mock
    private ShareCardService shareCardService;

    private ShareController controller;

    private String usedCode;

    @BeforeEach
    void setUp() {
        ClientIpResolver resolver = new ClientIpResolver();
        // 直接采用连接对端地址，避免请求头伪造干扰用例
        ReflectionTestUtils.setField(resolver, "trustProxy", false);
        controller = new ShareController(shareService, photoService, shareCardService, resolver);

        // 口令错误：接口返回 403（用于区分“未被限流”与“被限流”）
        lenient().when(shareService.getByCode(any(), any()))
                .thenThrow(new BusinessException(403, "访问口令不正确"));
    }

    @AfterEach
    void tearDown() {
        RateLimiter.clear("share-access:ip:" + IP);
        RateLimiter.clear("share-access:ip:" + OTHER_IP);
        if (usedCode != null) {
            RateLimiter.clear("share-access:code:" + usedCode + ":" + IP);
            RateLimiter.clear("share-access:code:" + usedCode + ":" + OTHER_IP);
        }
    }

    private MockHttpServletRequest requestFrom(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(ip);
        return request;
    }

    private int attemptsWithWrongCode(String code, String ip, int times) {
        MockHttpServletRequest request = requestFrom(ip);
        int lastCode = 0;
        for (int i = 0; i < times; i++) {
            Result<ShareLinkDTO> result = controller.getShareLink(code, "wrong-code", request);
            lastCode = result.getCode();
        }
        return lastCode;
    }

    @Test
    @DisplayName("同一分享码连续尝试：前 5 次放行，第 6 次返回 429")
    void rateLimitedAfterThreshold() {
        usedCode = "CodeA001";

        assertThat(attemptsWithWrongCode(usedCode, IP, ALLOWED_ATTEMPTS))
                .as("阈值内的尝试应正常走到口令校验（403），说明限流未误伤")
                .isEqualTo(403);
        assertThat(attemptsWithWrongCode(usedCode, IP, 1))
                .as("超过阈值后必须拒绝，否则口令可被持续穷举")
                .isEqualTo(429);
    }

    @Test
    @DisplayName("换一个分享码：不受其它链接的限流影响")
    void limitIsScopedToCodeAndIp() {
        usedCode = "CodeA002";
        assertThat(attemptsWithWrongCode(usedCode, IP, ALLOWED_ATTEMPTS + 1)).isEqualTo(429);

        assertThat(attemptsWithWrongCode("CodeB002", IP, 1))
                .as("限流键包含分享码，不应波及其它链接")
                .isEqualTo(403);
    }

    @Test
    @DisplayName("换一个来源地址：不受影响（避免限流被用作对正常访客的拒绝服务）")
    void limitIsScopedToSourceAddress() {
        usedCode = "CodeA003";
        assertThat(attemptsWithWrongCode(usedCode, IP, ALLOWED_ATTEMPTS + 1)).isEqualTo(429);

        assertThat(attemptsWithWrongCode(usedCode, OTHER_IP, 1))
                .as("换来源地址后应恢复可用")
                .isEqualTo(403);
    }
}
