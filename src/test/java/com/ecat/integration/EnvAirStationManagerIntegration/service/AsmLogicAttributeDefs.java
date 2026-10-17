/*
 * Copyright (c) 2026 ECAT Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ecat.integration.EnvAirStationManagerIntegration.service;

import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.UnitInfo;
import com.ecat.integration.logicdevice.LogicState.CommandAttrDef;
import com.ecat.integration.logicdevice.LogicState.LCommandAttribute;
import com.ecat.integration.logicdevice.LogicState.LNumericAttribute;
import com.ecat.integration.logicdevice.LogicState.LStringSelectAttribute;
import com.ecat.integration.logicdevice.LogicState.LTextAttribute;
import com.ecat.integration.logicdevice.LogicState.LTimeAttribute;
import com.ecat.integration.logicdevice.LogicState.LogicAttributeDefine;
import com.ecat.integration.logicdevice.LogicState.StringSelectAttrDef;

import java.util.List;

/** ASM 测试用真实逻辑属性定义工厂；禁止裸 Java 类型进入 LogicDevice boot 装配。 */
public final class AsmLogicAttributeDefs {

    private AsmLogicAttributeDefs() {
    }

    public static LogicAttributeDefine numericDef(String attrId, AttributeClass attrClass,
        UnitInfo nativeUnit, UnitInfo displayUnit, int precision) {
        return new LogicAttributeDefine(attrId, attrClass, nativeUnit, displayUnit,
            precision, false, LNumericAttribute.class);
    }

    public static LogicAttributeDefine textDef(String attrId, AttributeClass attrClass) {
        return new LogicAttributeDefine(attrId, attrClass, null, null,
            0, false, LTextAttribute.class);
    }

    public static LogicAttributeDefine timeDef(String attrId, AttributeClass attrClass) {
        return new LogicAttributeDefine(attrId, attrClass, null, null,
            0, false, LTimeAttribute.class);
    }

    public static StringSelectAttrDef selectDef(String attrId, AttributeClass attrClass,
        boolean mapable, List<String> options) {
        return new StringSelectAttrDef(attrId, attrClass, null, null,
            0, false, LStringSelectAttribute.class, mapable, options);
    }

    public static CommandAttrDef commandDef(String attrId, AttributeClass attrClass,
        boolean mapable, List<String> commands) {
        return new CommandAttrDef(attrId, attrClass, null, null,
            0, false, LCommandAttribute.class, mapable, commands);
    }
}
