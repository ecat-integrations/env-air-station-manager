package com.ecat.integration.EnvAirStationManagerIntegration.support;

import com.ecat.core.State.AttributeBase;
import com.ecat.integration.logicdevice.LogicState.LCommandAttribute;

/**
 * 控制动作类型（按 attr 类型派生，审计列 action）：
 * Command 型属性（{@link LCommandAttribute}）下发命令记 COMMAND，其余可写属性写值记 WRITE。
 *
 * @author coffee
 */
public enum AsmControlAction {
    WRITE,
    COMMAND;

    /** 按 attr 运行时类型派生（Command 型=setDisplayValue 即命令下发，参照控制链路调研结论）。 */
    public static AsmControlAction of(AttributeBase<?> attr) {
        return attr instanceof LCommandAttribute ? COMMAND : WRITE;
    }
}
