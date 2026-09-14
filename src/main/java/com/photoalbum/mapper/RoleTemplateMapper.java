package com.photoalbum.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 角色模板（功能权限）与模板能力位（只读）
 *
 * 模板决定：① 数据范围（管理侧默认可见集）② 预设能力位。
 * 新增子管理员角色 = 加一行模板 + 若干能力位，无需改鉴权代码。
 */
@Mapper
public interface RoleTemplateMapper {

    @Select("SELECT data_scope FROM t_role_template WHERE code = #{code}")
    String selectDataScope(@Param("code") String code);

    @Select("SELECT capability FROM t_role_capability WHERE role_code = #{code}")
    List<String> selectCapabilities(@Param("code") String code);

    @Select("SELECT t.code, t.name, t.description, t.data_scope AS dataScope, t.is_system AS isSystem, "
            + "GROUP_CONCAT(c.capability ORDER BY c.capability) AS capabilities "
            + "FROM t_role_template t LEFT JOIN t_role_capability c ON c.role_code = t.code "
            + "GROUP BY t.id, t.code, t.name, t.description, t.data_scope, t.is_system "
            + "ORDER BY t.sort_order, t.id")
    List<Map<String, Object>> selectTemplates();
}
