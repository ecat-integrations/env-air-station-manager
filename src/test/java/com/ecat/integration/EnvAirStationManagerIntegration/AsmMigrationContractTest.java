package com.ecat.integration.EnvAirStationManagerIntegration;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 4.0 pre-release ASM 单基线契约：稳压规则终态直接落在 V4.0.0。 */
class AsmMigrationContractTest {

    @Test
    void regulatedPowerRuleShouldUseVoltageRegulatorChannels() throws IOException {
        String sql = readBaseline();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "\\('regulated_voltage_abnormal', '0', '([^']+)'").matcher(sql);
        assertTrue(matcher.find(), "regulated_voltage_abnormal baseline rule is missing");
        JSONObject content = JSONObject.parseObject(matcher.group(1));

        assertEquals("logicdevice_station.voltage_regulator",
                content.getJSONObject("device_info").keySet().iterator().next());
        assertTrue(content.getJSONObject("device_info")
                .getJSONArray("logicdevice_station.voltage_regulator")
                .contains("voltage_ch1"));
        assertEquals("[198,242]", content.getJSONArray("configs").getJSONObject(0).getJSONArray("value").toString());
    }

    @Test
    void preReleaseBaselineShouldBeTheOnlyVersionedScript() throws IOException {
        Path migrationRoot = Paths.get(System.getProperty("user.dir"),
                "src/main/resources/db/migration-asm");
        List<Path> files = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.list(migrationRoot)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".sql")).forEach(files::add);
        }
        files.sort((left, right) -> left.getFileName().compareTo(right.getFileName()));
        assertEquals(1, files.size(), "4.0 pre-release must keep only V4.0.0 baseline");
        assertEquals("V4.0.0__init.sql", files.get(0).getFileName().toString());
    }

    private String readBaseline() throws IOException {
        try (InputStream in = getClass().getResourceAsStream(
                "/db/migration-asm/V4.0.0__init.sql")) {
            assertNotNull(in, "ASM V4.0.0 baseline is missing");
            byte[] bytes = new byte[in.available()];
            int read = in.read(bytes);
            assertEquals(bytes.length, read);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}
