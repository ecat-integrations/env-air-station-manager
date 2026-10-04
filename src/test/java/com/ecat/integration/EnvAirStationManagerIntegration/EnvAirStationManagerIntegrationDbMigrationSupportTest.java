package com.ecat.integration.EnvAirStationManagerIntegration;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.ecat.core.Integration.IntegrationRegistry;
import com.ecat.integration.EcatCoreRuoyiIntegration.EcatCoreRuoyiIntegration;
import com.ecat.integration.EcatDbMigration.DbMigrationFacade;
import com.ecat.integration.EcatDbMigration.dto.MigrationResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 本域自迁移接线薄测：测接线语义（两态分支/失败穿透/TCCL 零触碰/常量-目录一致），
 * 引擎行为归 db-migration 引擎仓测试，本测全模拟零真库。
 */
public class EnvAirStationManagerIntegrationDbMigrationSupportTest {

    private static final String DOMAIN = "asm";

    private static final String LOCATION = "classpath:db/migration-asm";

    private static final MigrationResult MIGRATED = MigrationResult.builder()
            .domain(DOMAIN).appliedCount(1).success(true).build();

    private EnvAirStationManagerIntegration integrationWith(IntegrationRegistry registry,
            EcatCoreRuoyiIntegration bridge, DataSource ds) {
        EnvAirStationManagerIntegration integration = new EnvAirStationManagerIntegration();
        setRegistry(integration, registry);
        when(registry.getIntegration("integration-ecat-core-ruoyi")).thenReturn(bridge);
        when(bridge.getSpringBean(DataSource.class)).thenReturn(ds);
        return integration;
    }

    /** 反射缝注入 registry(protected 字段跨包不可直写;仅测试面使用)。 */
    private static void setRegistry(EnvAirStationManagerIntegration integration, IntegrationRegistry registry) {
        setField(integration, "integrationRegistry", registry);
    }

    private static void setField(EnvAirStationManagerIntegration integration, String name, Object value) {
        try {
            Field field = integration.getClass().getSuperclass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(integration, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("注入 " + name + " 失败", e);
        }
    }

    @Test
    public void bridgeMissingThrowsWithDomainInMessage() {
        try (MockedStatic<DbMigrationFacade> facade = mockStatic(DbMigrationFacade.class)) {
            EnvAirStationManagerIntegration integration = new EnvAirStationManagerIntegration();
            setRegistry(integration, mock(IntegrationRegistry.class));

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    integration::migrateOwnDomain);

            assertTrue(ex.getMessage().contains(DOMAIN), "异常消息须含域名: " + ex.getMessage());
            facade.verifyNoInteractions();
        }
    }

    @Test
    public void dataSourceMissingThrowsWithDomainInMessage() {
        try (MockedStatic<DbMigrationFacade> facade = mockStatic(DbMigrationFacade.class)) {
            EcatCoreRuoyiIntegration bridge = mock(EcatCoreRuoyiIntegration.class);
            when(bridge.getSpringBean(DataSource.class)).thenReturn(null);
            EnvAirStationManagerIntegration integration = integrationWith(mock(IntegrationRegistry.class), bridge, null);

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    integration::migrateOwnDomain);

            assertTrue(ex.getMessage().contains(DOMAIN), "异常消息须含域名: " + ex.getMessage());
            facade.verifyNoInteractions();
        }
    }

    @Test
    public void facadeFailurePassesThroughUnchanged() {
        try (MockedStatic<DbMigrationFacade> facade = mockStatic(DbMigrationFacade.class)) {
            DataSource ds = mock(DataSource.class);
            EnvAirStationManagerIntegration integration = integrationWith(mock(IntegrationRegistry.class),
                    mock(EcatCoreRuoyiIntegration.class), ds);
            RuntimeException boom = new RuntimeException("boom");
            facade.when(() -> DbMigrationFacade.hasHistoryTable(DOMAIN, ds)).thenReturn(true);
            facade.when(() -> DbMigrationFacade.migrate(DOMAIN, ds,
                    Collections.singletonList(LOCATION), EnvAirStationManagerIntegration.class)).thenThrow(boom);

            RuntimeException caught = assertThrows(RuntimeException.class, integration::migrateOwnDomain);

            assertSame(boom, caught, "门面异常须原样穿透,禁包装/吞没");
        }
    }

    @Test
    public void historyTablePresentRoutesMigrateOnlyWithoutBaseline() {
        try (MockedStatic<DbMigrationFacade> facade = mockStatic(DbMigrationFacade.class)) {
            DataSource ds = mock(DataSource.class);
            EnvAirStationManagerIntegration integration = integrationWith(mock(IntegrationRegistry.class),
                    mock(EcatCoreRuoyiIntegration.class), ds);
            facade.when(() -> DbMigrationFacade.hasHistoryTable(DOMAIN, ds)).thenReturn(true);
            facade.when(() -> DbMigrationFacade.migrate(DOMAIN, ds,
                    Collections.singletonList(LOCATION), EnvAirStationManagerIntegration.class)).thenReturn(MIGRATED);

            integration.migrateOwnDomain();

            facade.verify(() -> DbMigrationFacade.migrate(DOMAIN, ds,
                    Collections.singletonList(LOCATION), EnvAirStationManagerIntegration.class), times(1));
            facade.verify(() -> DbMigrationFacade.baseline(anyString(), any(DataSource.class), anyString()),
                    never());
        }
    }

    @Test
    public void historyTableAbsentBaselinesZeroThenMigratesInOrder() {
        try (MockedStatic<DbMigrationFacade> facade = mockStatic(DbMigrationFacade.class)) {
            DataSource ds = mock(DataSource.class);
            EnvAirStationManagerIntegration integration = integrationWith(mock(IntegrationRegistry.class),
                    mock(EcatCoreRuoyiIntegration.class), ds);
            List<String> calls = new ArrayList<>();
            facade.when(() -> DbMigrationFacade.hasHistoryTable(DOMAIN, ds)).thenReturn(false);
            facade.when(() -> DbMigrationFacade.baseline(eq(DOMAIN), eq(ds), eq("0")))
                    .then(inv -> { calls.add("baseline"); return null; });
            facade.when(() -> DbMigrationFacade.migrate(eq(DOMAIN), eq(ds), anyList(),
                    eq(EnvAirStationManagerIntegration.class)))
                    .then(inv -> { calls.add("migrate"); return MIGRATED; });

            integration.migrateOwnDomain();

            assertEquals(Arrays.asList("baseline", "migrate"), calls, "空库须先 baseline(\"0\") 再 migrate");
            facade.verify(() -> DbMigrationFacade.baseline(DOMAIN, ds, "0"), times(1));
            facade.verify(() -> DbMigrationFacade.migrate(DOMAIN, ds,
                    Collections.singletonList(LOCATION), EnvAirStationManagerIntegration.class), times(1));
        }
    }

    @Test
    public void tcclUntouchedOnSuccessPath() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader sentinel = new ClassLoader() { };
        Thread.currentThread().setContextClassLoader(sentinel);
        try (MockedStatic<DbMigrationFacade> facade = mockStatic(DbMigrationFacade.class)) {
            DataSource ds = mock(DataSource.class);
            EnvAirStationManagerIntegration integration = integrationWith(mock(IntegrationRegistry.class),
                    mock(EcatCoreRuoyiIntegration.class), ds);
            facade.when(() -> DbMigrationFacade.hasHistoryTable(DOMAIN, ds)).thenReturn(true);
            facade.when(() -> DbMigrationFacade.migrate(anyString(), any(DataSource.class), anyList(), any()))
                    .thenReturn(MIGRATED);

            integration.migrateOwnDomain();

            assertSame(sentinel, Thread.currentThread().getContextClassLoader(),
                    "调用方零线程状态操作:TCCL 不得被触碰");
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    public void tcclUntouchedOnFacadeFailurePath() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader sentinel = new ClassLoader() { };
        Thread.currentThread().setContextClassLoader(sentinel);
        try (MockedStatic<DbMigrationFacade> facade = mockStatic(DbMigrationFacade.class)) {
            DataSource ds = mock(DataSource.class);
            EnvAirStationManagerIntegration integration = integrationWith(mock(IntegrationRegistry.class),
                    mock(EcatCoreRuoyiIntegration.class), ds);
            facade.when(() -> DbMigrationFacade.hasHistoryTable(DOMAIN, ds)).thenReturn(false);
            facade.when(() -> DbMigrationFacade.baseline(anyString(), any(DataSource.class), anyString()))
                    .thenThrow(new RuntimeException("baseline boom"));

            assertThrows(RuntimeException.class, integration::migrateOwnDomain);

            assertSame(sentinel, Thread.currentThread().getContextClassLoader(),
                    "异常路径同样不得触碰 TCCL");
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    public void constantsMatchScriptDirectoryOnClasspath() throws Exception {
        Field domainField = EnvAirStationManagerIntegration.class.getDeclaredField("DB_DOMAIN");
        domainField.setAccessible(true);
        assertEquals(DOMAIN, domainField.get(null));

        Field locationField = EnvAirStationManagerIntegration.class.getDeclaredField("DB_LOCATION");
        locationField.setAccessible(true);
        assertEquals(LOCATION, locationField.get(null));

        assertNotNull(EnvAirStationManagerIntegration.class.getResource("/db/migration-asm/V4.0.0__init.sql"),
                "常量与脚本目录零漂:V4.0.0__init.sql 须在本仓 classpath db/migration-asm/ 下");
    }
}
