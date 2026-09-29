package com.photoalbum.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MyBatis-Plus 插件注册测试（对应安全审查报告 H01）
 *
 * 缺陷回顾：分页不是 MyBatis-Plus 的自动行为，必须显式注册 PaginationInnerInterceptor，
 * 否则 selectPage 不生成 LIMIT —— 会静默返回全表且 total 失去意义。这类缺失不会在功能测试中暴露。
 *
 * 本测试把“拦截器已注册、方言正确、单页硬上限与 MAX_PAGE_SIZE 一致”固化为自动化约束，
 * 避免后续重构（例如误删配置类）让缺陷回归。
 */
@DisplayName("MyBatis-Plus 插件配置（H01 修复验证）")
class MybatisPlusConfigTest {

    @Test
    @DisplayName("注册了分页拦截器，方言为 MySQL，且单页硬上限等于 MAX_PAGE_SIZE")
    void registersPaginationInterceptorWithMaxLimit() {
        MybatisPlusInterceptor interceptor = new MybatisPlusConfig().mybatisPlusInterceptor();

        List<InnerInterceptor> inners = interceptor.getInterceptors();
        assertThat(inners)
                .as("必须注册且仅注册分页拦截器（缺失即分页失效）")
                .hasSize(1);
        assertThat(inners.get(0)).isInstanceOf(PaginationInnerInterceptor.class);

        PaginationInnerInterceptor pagination = (PaginationInnerInterceptor) inners.get(0);
        assertThat(pagination.getDbType())
                .as("方言需与生产库一致，否则分页 SQL 生成方式不同")
                .isEqualTo(DbType.MYSQL);
        assertThat(pagination.getMaxLimit())
                .as("硬上限必须与入参收敛共用同一常量，避免两处取值漂移")
                .isEqualTo((long) MybatisPlusConfig.MAX_PAGE_SIZE);
    }

    @Test
    @DisplayName("页码越界不回到第一页（避免前端把“翻过头”当成“有数据”）")
    void overflowIsDisabled() {
        MybatisPlusInterceptor interceptor = new MybatisPlusConfig().mybatisPlusInterceptor();
        PaginationInnerInterceptor pagination =
                (PaginationInnerInterceptor) interceptor.getInterceptors().get(0);

        assertThat(pagination.isOverflow()).isFalse();
    }

    @Test
    @DisplayName("单页硬上限取值不小于前端依赖的大页请求（后台选择器用 pageSize=500）")
    void maxPageSizeCoversFrontendUsage() {
        assertThat(MybatisPlusConfig.MAX_PAGE_SIZE)
                .as("上限若小于 500，后台的合集照片选择器与授权对象选择器会静默缺项")
                .isGreaterThanOrEqualTo(500);
    }
}
