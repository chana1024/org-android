# org-java 使用文档

`org-java` 是一个功能强大的 Java 库，专门用于解析（Parse）和生成（Generate） [Org-mode](https://orgmode.org/) 格式的文件。它被著名的 Android 应用 [Orgzly](https://www.orgzly.com/) 所使用，具有稳定性和完整的特性集。

本文档将指导您如何将 `org-java` 集成到您的项目中，并介绍其核心功能的使用方法。

## 1. 安装

您可以根据您的项目构建工具，选择以下方式将 `org-java` 添加为依赖。

**Gradle:**
```groovy
dependencies {
    implementation 'com.orgzly:org-java:1.2.2'
    // org-java 内部使用 joda-time
    implementation 'joda-time:joda-time:2.10.5'
}
```

**Maven:**
```xml
<dependency>
    <groupId>com.orgzly</groupId>
    <artifactId>org-java</artifactId>
    <version>1.2.2</version>
</dependency>
<dependency>
    <groupId>joda-time</groupId>
    <artifactId>joda-time</artifactId>
    <version>2.10.5</version>
</dependency>
```

## 2. 核心概念

在开始使用前，了解几个核心类很重要：

*   `OrgParser`: 解析器的入口，使用 `Builder` 模式进行配置和构建。
*   `OrgParsedFile`: 解析一个 Org 文件后得到的顶层对象，包含了文件元数据（`OrgFile`）和所有标题节点（Headlines）。
*   `OrgNodeInList` / `OrgNode`: 代表一个 Org 标题（Headline）及其层级和内容。`OrgNode` 包含了标题的详细信息。
*   `OrgHead`: `OrgNode` 的核心部分，包含了标题名、状态 (TODO/DONE)、标签 (Tags)、属性 (Properties)、计划/截止日期等。
*   `OrgDateTime`: 用于表示和操作 Org 模式中的各种时间戳，如 `<2025-09-11 Thu 10:00>`。
*   `OrgWriter`: 用于将 `OrgNode` 对象重新序列化为字符串。

## 3. 主要用法

### 3.1 解析一个 Org 文件/字符串

解析是 `org-java` 最核心的功能。您可以通过 `OrgParser.Builder` 来创建一个解析器实例。

**基本步骤:** 

1.  创建一个 `OrgParser.Builder` 实例。
2.  使用 `.setInput()` 方法提供要解析的字符串或 `Reader`。
3.  （可选）使用 `.setTodoKeywords()` 等方法配置解析器行为。
4.  调用 `.build()` 创建 `OrgParser` 实例。
5.  调用 `.parse()` 执行解析，返回一个 `OrgParsedFile` 对象。

**代码示例：**

```java
import com.orgzly.org.parser.OrgParser;
import com.orgzly.org.parser.OrgParsedFile;
import com.orgzly.org.parser.OrgNodeInList;
import com.orgzly.org.OrgHead;
import java.io.IOException;

public class OrgJavaExample {
    public static void main(String[] args) {
        String orgContent = "#+TITLE: My Awesome Org File\n\n" +
                            "* TODO Task 1 :work:project:\n" +
                            "  SCHEDULED: <2025-09-12 Fri>\n" +
                            "  This is the content of task 1.\n" +
                            "** DONE Subtask 1.1\n" +
                            "   CLOSED: [2025-09-11 Thu 15:00]\n";

        try {
            // 1. 创建 Builder 并设置输入
            OrgParser.Builder builder = new OrgParser.Builder();
            builder.setInput(orgContent);

            // 2. (可选) 配置自定义的 TODO 状态
            builder.setTodoKeywords(new String[]{"TODO", "IN-PROGRESS"});
            builder.setDoneKeywords(new String[]{"DONE", "CANCELLED"});

            // 3. 构建并解析
            OrgParser parser = builder.build();
            OrgParsedFile parsedFile = parser.parse();

            // 4. 访问解析结果
            // 获取文件标题
            String fileTitle = parsedFile.getFile().getSettings().getTitle();
            System.out.println("File Title: " + fileTitle);

            // 遍历所有标题节点
            for (OrgNodeInList node : parsedFile.getHeadsInList()) {
                OrgHead head = node.getHead();
                System.out.println("--------------------");
                System.out.println("Level: " + node.getLevel());
                System.out.println("Title: " + head.getTitle());
                System.out.println("State: " + head.getState());
                System.out.println("Tags: " + String.join(", ", head.getTags()));

                if (head.getScheduled() != null) {
                    System.out.println("Scheduled: " + head.getScheduled().getStartTime().toString());
                }
                 if (head.getContent() != null && !head.getContent().isEmpty()) {
                    System.out.println("Content: " + head.getContent().trim());
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
```

### 3.2 处理日期和时间 (`OrgDateTime`)

`OrgDateTime` 类封装了 Org 时间戳的复杂性，包括活动/非活动时间、重复器和延迟。

**创建和解析时间戳：**

```java
import com.orgzly.org.datetime.OrgDateTime;
import java.util.Calendar;

// 解析一个标准时间戳字符串
OrgDateTime scheduledTime = OrgDateTime.parse("<2025-09-12 Fri 10:30 +1w>");

// 检查属性
System.out.println("Is active: " + scheduledTime.isActive()); // true
System.out.println("Has time: " + scheduledTime.hasTime());   // true
System.out.println("Has repeater: " + scheduledTime.hasRepeater()); // true

// 获取 Calendar 对象
Calendar calendar = scheduledTime.getCalendar();
System.out.println("Year: " + calendar.get(Calendar.YEAR)); // 2025

// 使用 Builder 创建一个新的时间戳
OrgDateTime newDeadline = new OrgDateTime.Builder()
        .setIsActive(true) // Active timestamp: <>
        .setHasTime(true)
        .setYear(2025)
        .setMonth(Calendar.SEPTEMBER) // 月份从0开始
        .setDay(15)
        .setHour(18)
        .setMinute(0)
        .build();

System.out.println("New Deadline: " + newDeadline.toString()); // <2025-09-15 Mon 18:00>
```

### 3.3 操作属性 (`OrgProperties`)

您可以轻松地从一个标题中读取、添加或修改属性。

```java
import com.orgzly.org.OrgHead;
import com.orgzly.org.OrgProperties;

// 假设 'head' 是从解析结果中获取的 OrgHead 对象
OrgHead head = parsedFile.getHeadsInList().get(0).getHead();

// 添加/设置属性
head.getProperties().put("ID", "task-001");
head.getProperties().set("PRIORITY", "High"); // .set 会覆盖已有的同名属性

// 读取属性
String priority = head.getProperties().get("PRIORITY");
System.out.println("Priority: " + priority); // "High"

// 特殊的追加语法: KEY+
head.getProperties().put("VAR", "a=1");
head.getProperties().put("VAR+", "b=2"); // 追加到 VAR 属性
System.out.println(head.getProperties().get("VAR")); // "a=1 b=2"
```

### 3.4 生成 Org 格式字符串 (`OrgWriter`)

虽然 `OrgParsedFile.toString()` 可以将整个文件转回字符串，但如果你想单独处理某个部分，`OrgWriter` (及其内部实现 `OrgParserWriter`) 提供了更细粒度的控制。

在 `OrgParserTest.java` 的示例中，`OrgParserWriter` 被用来将解析后的对象转换回格式化的字符串，以进行断言比较。这表明 `toString()` 方法是生成输出的主要方式。

**示例 (基于测试用例的模式):**

```java
// 'parsedFile' 是解析后得到的 OrgParsedFile 对象
// 调用 toString() 会使用默认或解析时设置的格式化选项
String regeneratedContent = parsedFile.toString();

System.out.println(regeneratedContent);
```

## 4. 总结

`org-java` 是一个成熟且功能完备的库，用于在 JVM 环境中处理 Org-mode 文件。其主要优势在于：

*   **强大的解析器**：支持 Org 模式的各种复杂语法，包括多种时间戳格式、属性抽屉、日志等。
*   **灵活的配置**：通过 `Builder` 模式可以轻松定制 TODO 关键字等行为。
*   **健壮的数据模型**：提供了清晰的 Java 对象来表示 Org 文件的各个部分，便于程序化访问和修改。
*   **可靠的序列化**：能够将修改后的对象准确地写回 Org 格式。

通过本文档提供的示例，您应该能够快速上手，在您的 Java 项目中集成强大的 Org-mode 文件处理能力。如需更深入的了解，建议直接阅读 `src/test/java` 目录下的测试用例，它们是该库最全面的“活文档”。
