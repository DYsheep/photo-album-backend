package com.photoalbum.controller;

import com.photoalbum.common.Result;
import com.photoalbum.entity.AuditLog;
import com.photoalbum.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 操作审计查询（仅管理员：user:manage）
 *
 * 用于追溯授权条目与账号管理动作：谁在什么时候对谁做了什么。
 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('user:manage')")
public class AdminAuditController {

    private final AuditService auditService;

    /**
     * 最近的授权与账号操作日志
     * GET /api/admin/audit-logs?size=50
     */
    @GetMapping("/api/admin/audit-logs")
    public Result<List<Map<String, Object>>> list(
            @RequestParam(value = "size", defaultValue = "50") int size) {
        List<AuditLog> logs = auditService.recent(size);
        List<Map<String, Object>> result = new ArrayList<>(logs.size());
        for (AuditLog log : logs) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", log.getId());
            item.put("actorName", log.getActorName());
            item.put("action", log.getAction());
            item.put("targetType", log.getTargetType());
            item.put("targetId", log.getTargetId());
            item.put("detail", log.getDetail());
            item.put("ip", log.getIp());
            item.put("createdAt", log.getCreatedAt());
            result.add(item);
        }
        return Result.ok(result);
    }
}
