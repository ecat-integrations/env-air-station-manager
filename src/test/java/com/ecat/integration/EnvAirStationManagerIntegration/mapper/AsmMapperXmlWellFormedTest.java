package com.ecat.integration.EnvAirStationManagerIntegration.mapper;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * ASM 全部 mapper XML well-formed 护栏。
 *
 * <p><b>应用场景</b>：2026-08-18 上线熔断的根因是 AsmHistoryQueryMapper.xml 残留重复 SQL 片段
 * （多余 {@code </select>}）导致 XML 不闭合，运行时 DynamicJarLoader.registerMappers 的
 * XMLMapperBuilder 抛 SAXParseException，9 个 mapper 全体注册失败 → @Service bean 装配级联 NPE。
 * 既有 AsmMapperNamespaceTest 只解析 5/9 个 XML，未覆盖 AsmHistoryQueryMapper.xml 等四个，
 * 181 测全绿也拦不住。</p>
 *
 * <p><b>为什么用 javax.xml DOM 而非 XMLMapperBuilder</b>：本测试只守 well-formed（标签闭合/
 * 转义合法）这一层，不需要 MyBatis Configuration 语义；DOM 解析对每个文件独立报错，能逐文件
 * 指认破坏点，且不依赖 mapper 接口注册顺序。文件集经文件系统扫描自动纳管（新增 mapper xml
 * 免改本测试）。</p>
 *
 * @author coffee
 */
class AsmMapperXmlWellFormedTest {

    /** mapper xml 目录（surefire 工作目录=模块根）。 */
    private static final File MAPPER_DIR = new File("src/main/resources/mapper");

    @Test
    void allMapperXmlsAreWellFormed() {
        File[] files = MAPPER_DIR.listFiles((dir, name) -> name.endsWith(".xml"));
        assertTrue(files != null && files.length > 0,
                "mapper xml 目录不存在或为空: " + MAPPER_DIR.getAbsolutePath());
        List<String> broken = new ArrayList<>();
        for (File f : files) {
            try {
                DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
                dbf.setNamespaceAware(false);
                // 禁外部 DTD 加载：mapper 头部 DOCTYPE 指向 mybatis.org，裸解析会按 URL 联网拉取，
                // 网络不可达时 Xerces 无超时控制会吊死构建（运行时 MyBatis 走 jar 内置 DTD 从不联网）
                dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
                Document doc = dbf.newDocumentBuilder().parse(f);
                assertTrue(doc.getDocumentElement() != null, f.getName() + " 无根元素");
            } catch (Exception e) {
                broken.add(f.getName() + ": " + e.getMessage());
            }
        }
        assertTrue(broken.isEmpty(),
                "well-formed 破坏的 mapper xml（运行时 DynamicJarLoader 将整链熔断）: " + broken);
    }
}
