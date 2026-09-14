package com.photoalbum.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 字段自动填充
 *
 * 背景：各实体用 @TableField(fill = FieldFill.INSERT) / INSERT_UPDATE 标注了 createdAt / updatedAt，
 * 但项目此前没有注册填充器 —— 这种情况下 MyBatis-Plus 会把这些列显式写入 NULL，
 * 数据库的 DEFAULT CURRENT_TIMESTAMP 不会生效，表现为审计日志、用户、照片等记录的创建时间为空。
 *
 * 策略：仅在字段为 null 时填充，不覆盖业务代码显式设置的值（strictXxxFill 已保证）。
 */
@Component
public class MyBatisFieldFillHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
        strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
    }
}
