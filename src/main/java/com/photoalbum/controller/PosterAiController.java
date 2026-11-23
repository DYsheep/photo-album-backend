package com.photoalbum.controller;

import com.photoalbum.common.Result;
import com.photoalbum.service.AiPosterService;
import com.photoalbum.service.PhotoService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 年度海报的 AI 素材（A + C）
 *
 * 只给管理员：AI 调用会产生费用，且返回的 prompt/模型信息属于运营细节。
 * 路径位于 /api/admin/**，由 SecurityConfig 统一要求 admin:access，
 * 这里再加方法级注解，避免日后调整路径规则时失去保护。
 */
@RestController
@RequestMapping("/api/admin/poster/ai")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('admin:access')")
public class PosterAiController {

    private final AiPosterService aiPosterService;
    private final PhotoService photoService;

    /**
     * 读取已缓存的 AI 素材（不触发生成，不产生费用）
     * GET /api/admin/poster/ai?year=2026
     */
    @GetMapping
    public Result<Map<String, Object>> cached(@RequestParam(value = "year", required = false) Integer year) {
        int target = resolveYear(year);
        return Result.ok(aiPosterService.cached(target));
    }

    /**
     * 生成（或补齐）AI 素材
     * POST /api/admin/poster/ai?year=2026&part=all|background|copy
     *
     * 背景图生成通常需要十几秒，前端应显示进行中状态。
     */
    @PostMapping
    public Result<Map<String, Object>> generate(
            @RequestParam(value = "year", required = false) Integer year,
            @RequestParam(value = "part", defaultValue = "all") String part) {
        int target = resolveYear(year);
        Map<String, Object> stats = photoService.getYearlyStats(target);
        return Result.ok(aiPosterService.generate(target, stats, part));
    }

    /** 未指定年份时取最近有照片的一年（与年度小结口径一致） */
    private int resolveYear(Integer year) {
        if (year != null) return year;
        Object latest = photoService.getYearlyStats(null).get("year");
        return latest == null ? java.time.LocalDate.now().getYear() : (Integer) latest;
    }
}
