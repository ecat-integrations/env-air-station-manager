-- ASM 鉴权权限行（P5a，结构对齐 ADM adm_auth.sql）：
-- env-air-station-manager 全部 controller 逐方法 @PreAuthorize 的权限 key（9 个 asm-monitor:*）。
-- 落 ruoyi sys_menu（perm 管理用；菜单显示由动态 jar module-config.json 负责，故本处仅建隐藏父目录 + F 按钮 perms）。
-- 库：ruoyi 主库（PostgreSQL）public schema。app 不自动跑；运维执行，幂等（DO 块守卫，可重跑）。
-- admin 角色（role_id=1）授予全部 asm-monitor:* —— admin 用户(user_id=1)持 *:*:* 通配本就全过，
-- 此授权让【非 user_id=1 的 admin 角色用户】也获得 asm-monitor:*（ruoyi:仅 user_id=1 isAdmin 享 *:*:*）。

DO $$
DECLARE
    p text;
    mid bigint;
    parent bigint;
BEGIN
    -- 隐藏父目录（visible='1' 隐藏，避与 module-config 菜单重复；仅作 perm 分组容器）
    SELECT menu_id INTO parent FROM sys_menu WHERE menu_name = '空气站房管理(权限)' AND menu_type = 'M';
    IF parent IS NULL THEN
        parent := (SELECT coalesce(max(menu_id), 0) + 1 FROM sys_menu);
        INSERT INTO sys_menu(menu_id, menu_name, parent_id, order_num, path, component, query, is_frame, is_cache,
                             menu_type, visible, status, perms, icon, create_time, update_time, remark)
        VALUES (parent, '空气站房管理(权限)', 0, 91, 'asm-perm', NULL, '', 1, 0,
                'M', '1', '0', '', '#', now(), now(),
                'ASM 监控权限父目录(隐藏:visible=1);菜单显示走动态 jar module-config.json,此仅 sys_menu perm 管理容器');
    END IF;

    -- 13 个 asm-monitor:* 权限（F 按钮），逐一对应 controller @PreAuthorize：
    --   AsmSnapshotController       snapshot           → asm-monitor:monitor:list
    --   AsmHistoryQueryController   history            → asm-monitor:history:query
    --   AsmAlarmRuleController      list/get/add/edit/remove → asm-monitor:alarmRule:*
    --   AsmAlarmRecordController    list               → asm-monitor:alarmRecord:list
    --   AsmControlController        execute            → asm-monitor:control:execute
    --   AsmControlRecordController  list               → asm-monitor:controlRecord:list
    --   AsmStatParamsController     list               → asm-monitor:statParams:list
    --   AsmConfigController         getStat/getUnit    → asm-monitor:config:read
    --   AsmConfigController         putStat/putUnit    → asm-monitor:config:edit
    FOREACH p IN ARRAY ARRAY[
        'asm-monitor:monitor:list',
        'asm-monitor:history:query',
        'asm-monitor:alarmRule:list',
        'asm-monitor:alarmRule:query',
        'asm-monitor:alarmRule:add',
        'asm-monitor:alarmRule:edit',
        'asm-monitor:alarmRule:remove',
        'asm-monitor:alarmRecord:list',
        'asm-monitor:control:execute',
        'asm-monitor:controlRecord:list',
        'asm-monitor:statParams:list',
        'asm-monitor:config:read',
        'asm-monitor:config:edit'
    ] LOOP
        IF NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = p) THEN
            mid := (SELECT coalesce(max(menu_id), 0) + 1 FROM sys_menu);
            INSERT INTO sys_menu(menu_id, menu_name, parent_id, order_num, path, component, query, is_frame, is_cache,
                                 menu_type, visible, status, perms, icon, create_time, update_time, remark)
            VALUES (mid, p, parent, 0, '', NULL, '', 1, 0,
                    'F', '0', '0', p, '#', now(), now(),
                    'ASM 权限(controller @PreAuthorize 逐方法对应)');
        END IF;
        -- 授 admin 角色(role_id=1)
        IF NOT EXISTS (SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON rm.menu_id = m.menu_id
                       WHERE rm.role_id = 1 AND m.perms = p) THEN
            INSERT INTO sys_role_menu(role_id, menu_id)
            VALUES (1, (SELECT menu_id FROM sys_menu WHERE perms = p));
        END IF;
    END LOOP;
END $$;
