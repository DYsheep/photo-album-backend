package com.photoalbum.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件配置
 *
 * 为什么必须有这个类：分页**不是** MyBatis-Plus 的自动行为，必须在拦截器链上显式注册
 * PaginationInnerInterceptor，否则 selectPage 不会生成 LIMIT —— 它会静默地把满足条件的
 * 记录全部查出并返回，同时 total 失去意义（表现为“分页参数形同虚设 + 列表接口返回全表”）。
 * 这类缺陷不会在功能测试中暴露（接口照常可用，只是数据量不对），因此在安全审查中单列。
 *
 * 本类同时设定了单页硬上限 MAX_PAGE_SIZE：
 *   · 与 PhotoServiceImpl 的入参收敛共用同一常量，避免两处取值漂移；
 *   · 即使调用方绕过入参校验直接构造 Page，也不会一次拉取超过该数量的记录；
 *   · 注意 MyBatis-Plus 的语义是 **size < 0 表示不分页**，所以入参侧必须把 pageSize 收敛到 ≥1，
 *     仅设上限并不能阻止 pageSize=-1 绕过（见 PhotoServiceImpl.getPhotoPage）。
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 单页最大条数（入参收敛与拦截器硬上限共用）
     *
     * 取 500 而非更小的值，是为了兼容前端既有的大页请求：后台的合集照片选择器与
     * 授权对象选择器都以 pageSize=500 拉取候选列表。上限若小于该值，这些选择器会静默缺项。
     */
    public static final int MAX_PAGE_SIZE = 500;

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        // 单页硬上限：兜住“入参校验被绕过”以及误用超大 pageSize 的情况
        pagination.setMaxLimit((long) MAX_PAGE_SIZE);
        // 请求页码超过最大页时返回空结果集，而不是回到第一页（避免前端把“翻过头”当成“有数据”）
        pagination.setOverflow(false);

        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
