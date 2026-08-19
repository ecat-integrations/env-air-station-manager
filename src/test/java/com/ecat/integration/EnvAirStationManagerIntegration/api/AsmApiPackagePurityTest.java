package com.ecat.integration.EnvAirStationManagerIntegration.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ASM api 包零依赖护栏——对外稳定契约包（接口 + 不可变 DTO）不得 import ruoyi/Spring
 * （外部消费方 maven 依赖 ASM jar provided，只 import api 包；内部实现不得反向泄漏）。
 * 允许：java.*、lombok（@Value/@Builder 编译期）、ASM 自身 support 纯枚举（AsmStatGranularity/AsmIntervalMode，
 * 与 ADM api 复用 AdmIntervalMode 同口径——纯枚举无内部依赖）。
 *
 * <p>ADM 无同款护栏测试，本测试为 ASM 新建（源文件 import 扫描，确定性无反射）。</p>
 *
 * @author coffee
 */
class AsmApiPackagePurityTest {

    private static final Path API_SRC = Paths.get(
            "src/main/java/com/ecat/integration/EnvAirStationManagerIntegration/api");

    @Test
    void apiPackageMustExistWithSources() throws IOException {
        assertTrue(Files.isDirectory(API_SRC), "api 包源码目录必须存在: " + API_SRC);
        List<Path> sources;
        try (Stream<Path> s = Files.list(API_SRC)) {
            sources = s.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        }
        assertFalse(sources.isEmpty(), "api 包须含 AirStationSdk 接口 + DTO 源文件");
    }

    @Test
    void apiSourcesMustNotImportRuoyiOrSpring() throws IOException {
        try (Stream<Path> s = Files.list(API_SRC)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".java")).collect(Collectors.toList())) {
                String src = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                assertFalse(src.contains("import com.ruoyi"),
                        p.getFileName() + " 不得 import ruoyi（api 包零宿主依赖）");
                assertFalse(src.contains("import org.springframework"),
                        p.getFileName() + " 不得 import Spring（api 包零 Spring 依赖）");
                assertFalse(src.contains("import com.ecat.core"),
                        p.getFileName() + " 不得 import ecat-core（api 包零 core 依赖，枚举经 ASM support 纯枚举承载）");
            }
        }
    }
}
