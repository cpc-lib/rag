/*
 Navicat Premium Dump SQL

 Source Server         : 192.168.1.200
 Source Server Type    : MySQL
 Source Server Version : 80411 (8.4.11)
 Source Host           : 192.168.1.200:3306
 Source Schema         : rag

 Target Server Type    : MySQL
 Target Server Version : 80411 (8.4.11)
 File Encoding         : 65001

 Date: 20/09/2026 23:44:56
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for chat_message
-- ----------------------------
CREATE TABLE `chat_message`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `user_id` bigint NULL DEFAULT NULL,
  `session_id` bigint NULL DEFAULT NULL,
  `kb_id` bigint NOT NULL,
  `question` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `answer` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `citations` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '引用来源 JSON',
  `token_usage` int NOT NULL DEFAULT 0,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_tenant_time`(`tenant_id` ASC, `created_at` ASC) USING BTREE,
  INDEX `idx_tenant_user`(`tenant_id` ASC, `user_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 57 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '问答留痕' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of chat_message
-- ----------------------------
INSERT INTO `chat_message` VALUES (34, '705879', 3, 3, 3, 'MySQL 一条 SQL 是怎么执行的', 'MySQL 执行一条 SQL 语句的完整流程如下（依据资料归纳）：\n\n1. **连接器（Connector）**：  \n   - 建立客户端与 MySQL 服务器的连接；  \n   - 进行身份认证（如用户名、密码校验）和权限检查；  \n   - 维护连接上下文（如当前数据库、用户权限等）[3]。\n\n2. **解析器（Parser）**：  \n   - 进行词法分析（识别关键字、标识符、运算符等）和语法分析（验证 SQL 是否符合语法规则）；  \n   - 若存在语法错误（如 `SELECT * FORM user;` 中的 `FORM` 拼写错误），在此阶段直接报错；  \n   - 解析成功后生成抽象语法树（AST）[2]。\n\n3. **预处理器（Preprocessor）**：  \n   - 检查语义合法性，例如：  \n     - 表是否存在；  \n     - 字段名是否正确、是否存在歧义；  \n     - `*` 是否可展开为具体列；  \n     - 用户是否有对应操作权限。  \n   - 如 `SELECT abc FROM user;` 中字段 `abc` 不存在，则在此阶段报错 [4]。\n\n4. **查询优化器（Optimizer）**：  \n   - 基于统计信息（如表行数、索引基数 Cardinality、直方图等）生成多个候选执行计划，并选择成本最低的方案；  \n   - 决定是否使用索引、使用哪个索引（如 `idx_age`、`idx_status` 或复合索引 `idx_age_status`），或是否全表扫描；  \n   - 也决定多表 JOIN 的执行顺序（如优先过滤结果更少的表以减少中间数据量）[5][7][8]；  \n   - 注意：**有索引不等于一定用索引**，优化器可能因选择性差（如 `WHERE age > 1` 匹配 90% 数据）而选择全表扫描 [6]。\n\n5. **执行器（Executor）**：  \n   - 调用存储引擎接口执行优化后的计划；  \n   - 校验用户对表/字段的访问权限（再次权限检查）；  \n   - 处理执行过程中的锁、事务、日志等逻辑 [1]。\n\n6. **存储引擎（如 InnoDB）**：  \n   - 实际完成数据读写；  \n   - 利用 Buffer Pool 缓存页数据，必要时从磁盘加载；  \n   - 返回查询结果给执行器 [1]。\n\n7. **返回结果**：  \n   - 执行器将结果集格式化后返回给客户端 [1]。\n\n总结核心流程口诀：  \n> **连接 → 解析 → 预处理 → 优化 → 执行 → 返回** [1]。', '[{\"seq\":1,\"chunkId\":1047,\"documentId\":7,\"documentName\":\"17092026_mysql_redis_wechat_payment_notes.md\",\"page\":0,\"previewUrl\":\"http://192.168.1.200:9000/rag-files/705879/3/2026-09-19/d348bb43-bc2c-4a4f-85ef-44ffb51badce.md?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260919%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260919T183336Z&X-Amz-Expires=900&X-Amz-SignedHeaders=host&X-Amz-Signature=7cb30f8adb9560896066e47ae7126de7e085a28a48d0f2a1b0cdd40f78551aea\",\"content\":\"以：\\n```sql\\nSELECT id, name\\nFROM user\\nWHERE age = 20;\\n```\\n为例，一条查询 SQL 大致经历：\\n```text\\n客户端\\n   ↓\\n连接器 Connector\\n   ↓\\n解析器 Parser\\n   ↓\\n预处理器 Preprocessor\\n   ↓\\n查询优化器 Optimizer\\n   ↓\\n执行器 Executor\\n   ↓\\n存储引擎 InnoDB\\n   ↓\\nBuffer Pool / 磁盘\\n   ↓\\n返回结果\\n```\\n核心可以记成：\\n> **连接 → 解析 → 预处理 → 优化 → 执行 → 返回**\"},{\"seq\":2,\"chunkId\":1049,\"documentId\":7,\"documentName\":\"17092026_mysql_redis_wechat_payment_notes.md\",\"page\":0,\"previewUrl\":\"http://192.168.1.200:9000/rag-files/705879/3/2026-09-19/d348bb43-bc2c-4a4f-85ef-44ffb51badce.md?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260919%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260919T183336Z&X-Amz-Expires=900&X-Amz-SignedHeaders=host&X-Amz-Signature=7cb30f8adb9560896066e47ae7126de7e085a28a48d0f2a1b0cdd40f78551aea\",\"content\":\"主要进行：\\n- 词法分析\\n- 语法分析\\n比如：\\n```sql\\nSELECT * FORM user;\\n```\\n`FORM` 写错成了 `FROM`，这一阶段就会报语法错误\\n解析之后会形成类似 AST 的语法树\\n---\"},{\"seq\":3,\"chunkId\":1048,\"documentId\":7,\"documentName\":\"17092026_mysql_redis_wechat_payment_notes.md\",\"page\":0,\"previewUrl\":\"http://192.168.1.200:9000/rag-files/705879/3/2026-09-19/d348bb43-bc2c-4a4f-85ef-44ffb51badce.md?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260919%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260919T183336Z&X-Amz-Expires=900&X-Amz-SignedHeaders=host&X-Amz-Signature=7cb30f8adb9560896066e47ae7126de7e085a28a48d0f2a1b0cdd40f78551aea\",\"content\":\"负责：\\n- 建立连接\\n- 身份认证\\n- 权限检查\\n- 维护连接上下文\\n例如：\\n```bash\\nmysql -h127.0.0.1 -uroot -p\\n```\\n---\"},{\"seq\":4,\"chunkId\":1050,\"documentId\":7,\"documentName\":\"17092026_mysql_redis_wechat_payment_notes.md\",\"page\":0,\"previewUrl\":\"http://192.168.1.200:9000/rag-files/705879/3/2026-09-19/d348bb43-bc2c-4a4f-85ef-44ffb51badce.md?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260919%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260919T183336Z&X-Amz-Expires=900&X-Amz-SignedHeaders=host&X-Amz-Signature=7cb30f8adb9560896066e47ae7126de7e085a28a48d0f2a1b0cdd40f78551aea\",\"content\":\"主要负责检查：\\n- 表是否存在\\n- 字段是否存在\\n- 字段是否有歧义\\n- `*` 展开\\n- 权限等\\n比如：\\n```sql\\nSELECT abc FROM user;\\n```\\n如果 `abc` 不存在，会在这一阶段发现\\n---\"},{\"seq\":5,\"chunkId\":1051,\"documentId\":7,\"documentName\":\"17092026_mysql_redis_wechat_payment_notes.md\",\"page\":0,\"previewUrl\":\"http://192.168.1.200:9000/rag-files/705879/3/2026-09-19/d348bb43-bc2c-4a4f-85ef-44ffb51badce.md?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260919%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260919T183336Z&X-Amz-Expires=900&X-Amz-SignedHeaders=host&X-Amz-Signature=7cb30f8adb9560896066e47ae7126de7e085a28a48d0f2a1b0cdd40f78551aea\",\"content\":\"这是 SQL 执行过程中非常重要的一环\\n例如：\\n```sql\\nSELECT *\\nFROM user\\nWHERE age = 20\\nAND status = 1;\\n```\\n假设存在：\\n```text\\nidx_age\\nidx_status\\nidx_age_status\\n```\\nMySQL 优化器需要决定：\\n```text\\n走 idx_age？\\n还是 idx_status？\\n还是 idx_age_status？\\n还是全表扫描？\\n```\\n优化器会生成多个候选执行计划，然后基于成本模型选择估算成本最低的执行计划\\n因此：\\n> **MySQL 并不是“有索引就一定使用索引”\\n**\\n---\"},{\"seq\":6,\"chunkId\":1055,\"documentId\":7,\"documentName\":\"17092026_mysql_redis_wechat_payment_notes.md\",\"page\":0,\"previewUrl\":\"http://192.168.1.200:9000/rag-files/705879/3/2026-09-19/d348bb43-bc2c-4a4f-85ef-44ffb51badce.md?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260919%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260919T183336Z&X-Amz-Expires=900&X-Amz-SignedHeaders=host&X-Amz-Signature=7cb30f8adb9560896066e47ae7126de7e085a28a48d0f2a1b0cdd40f78551aea\",\"content\":\"不同条件对应的选择性不同\\n例如：\\n```sql\\nWHERE id = 100\\n```\\n如果 `id` 是主键，查询成本通常非常低\\n而：\\n```sql\\nWHERE age > 1\\n```\\n如果能匹配整表 90% 数据，则优化器可能直接全表扫描\\n---\"},{\"seq\":7,\"chunkId\":1054,\"documentId\":7,\"documentName\":\"17092026_mysql_redis_wechat_payment_notes.md\",\"page\":0,\"previewUrl\":\"http://192.168.1.200:9000/rag-files/705879/3/2026-09-19/d348bb43-bc2c-4a4f-85ef-44ffb51badce.md?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260919%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260919T183336Z&X-Amz-Expires=900&X-Amz-SignedHeaders=host&X-Amz-Signature=7cb30f8adb9560896066e47ae7126de7e085a28a48d0f2a1b0cdd40f78551aea\",\"content\":\"MySQL 优化器通常依赖统计信息，而不是先扫描全表再决定执行计划\\n常见统计信息包括：\\n- 表大约多少行\\n- 索引 Cardinality\\n- 数据分布\\n- Histogram 直方图\\n因此如果统计信息不准确，可能出现：\\n> SQL 明明适合走某个索引，却选择了其他索引甚至全表扫描\\n可以使用：\\n```sql\\nANALYZE TABLE user;\\n```\\n重新收集统计信息\\n---\"},{\"seq\":8,\"chunkId\":1058,\"documentId\":7,\"documentName\":\"17092026_mysql_redis_wechat_payment_notes.md\",\"page\":0,\"previewUrl\":\"http://192.168.1.200:9000/rag-files/705879/3/2026-09-19/d348bb43-bc2c-4a4f-85ef-44ffb51badce.md?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260919%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260919T183336Z&X-Amz-Expires=900&X-Amz-SignedHeaders=host&X-Amz-Signature=7cb30f8adb9560896066e47ae7126de7e085a28a48d0f2a1b0cdd40f78551aea\",\"content\":\"例如：\\n```sql\\nSELECT *\\nFROM orders o\\nJOIN user u\\nON o.user_id = u.id\\nWHERE u.id = 100;\\n```\\n优化器需要决定：\\n```text\\n先查 user\\n还是先查 orders\\n```\\n一般会尽量让过滤结果更少的表优先参与连接，从而减少后续 JOIN 的数据量\\n---\"}]', 1563, '2026-09-20 02:33:48');
INSERT INTO `chat_message` VALUES (35, '705879', 3, 3, 3, '衡阳明天天气如何', '根据当前可获取的天气实况信息，仅能提供衡阳**当前天气**：气温 20.5°C，湿度 90%，风速 1.3 km/h，天气状况为晴 [外部信息]。\n\n但您询问的是**明天天气**，而【资料】中未包含衡阳未来天气预报数据，【外部信息】也仅提供实时天气，未涵盖预报信息。因此，**无法从现有信息中获知衡阳明天的具体天气情况**。\n\n建议您通过权威气象服务平台（如中国气象局官网、中央气象台App或天气通等）查询最新预报。', '[]', 905, '2026-09-20 02:35:45');
INSERT INTO `chat_message` VALUES (36, '705879', 2, 4, 3, '衡阳明天天气如何', '根据当前资料，仅提供衡阳的实时天气信息（气温20.5°C、湿度90%、风速1.3 km/h、晴），未包含明日天气预报内容 [2]。建议您通过权威气象平台（如中国气象局官网或天气应用）查询最新预报。', '[]', 193, '2026-09-20 02:39:28');
INSERT INTO `chat_message` VALUES (37, '705879', 2, 4, 3, '衡阳明天天气如何', '衡阳明天（2026年9月21日）天气为雷暴，最高气温27.4°C，最低气温20.0°C，降水概率高达96% [2]。建议外出携带雨具，注意防雷避险。', '[]', 314, '2026-09-20 02:42:39');
INSERT INTO `chat_message` VALUES (38, '705879', 2, 4, 3, '衡阳近7天天气如何', '衡阳未来7天天气预报如下（数据截至当前可查信息）：\n\n- **2026-09-20**：毛毛雨，最高29.0°C，最低19.8°C，降水概率71%  \n- **2026-09-21**：雷暴，最高27.4°C，最低20.0°C，降水概率96%  \n- **2026-09-22**：雷暴，最高26.2°C，最低18.9°C，降水概率92%  \n- **2026-09-23**：毛毛雨，最高25.3°C，最低18.2°C，降水概率47%  \n- **2026-09-24**：多云，最高26.9°C，最低16.2°C，降水概率12%  \n- **2026-09-25**：多云，最高27.0°C，最低16.6°C，降水概率6%  \n- **2026-09-26**：毛毛雨，最高29.2°C，最低19.3°C，降水概率12% [2]\n\n温馨提示：21—22日雷暴频繁、降水概率极高，需注意防范强对流天气；24—25日天气转好，适宜户外活动。', '[]', 831, '2026-09-20 02:43:04');
INSERT INTO `chat_message` VALUES (39, '705879', 2, 5, 3, '衡阳近7天天气如何', '衡阳未来7天天气预报如下：\n\n- **9月20日**：毛毛雨，气温 20.0°C ~ 28.9°C，降水概率 71%  \n- **9月21日**：雷暴，气温 20.2°C ~ 27.8°C，降水概率 96%  \n- **9月22日**：雷暴，气温 19.1°C ~ 25.8°C，降水概率 92%  \n- **9月23日**：毛毛雨，气温 18.2°C ~ 25.6°C，降水概率 47%  \n- **9月24日**：多云，气温 16.9°C ~ 27.0°C，降水概率 12%  \n- **9月25日**：阴，气温 16.4°C ~ 27.1°C，降水概率 6%  \n- **9月26日**：毛毛雨，气温 19.1°C ~ 28.9°C，降水概率 12%  \n\n温馨提示：21–22日有强雷暴，建议减少外出，注意防雷防雨；24–25日天气较稳定，适宜户外活动。', '[]', 0, '2026-09-20 02:58:24');
INSERT INTO `chat_message` VALUES (40, '866818', 5, 6, 4, 'What is the weather like in Beijing today?', 'I don’t have access to real-time weather data, including today’s weather in Beijing. Please check a trusted weather service or app for the most up-to-date forecast.', '[]', 127, '2026-09-20 03:15:55');
INSERT INTO `chat_message` VALUES (41, '866818', 5, 7, 4, 'What is the weather like in Beijing today?', '北京今天的天气为阴，气温22.7°C，湿度77%，风速4.8 km/h。', '[]', 0, '2026-09-20 03:15:58');
INSERT INTO `chat_message` VALUES (42, '866818', 5, 8, 4, 'What is the weather like in Shanghai today?', 'I don’t have access to real-time weather data or current conditions in Shanghai. Please check a trusted weather service (e.g., Weather.com, AccuWeather, or a local meteorological agency) for today’s forecast.', '[]', 138, '2026-09-20 03:16:00');
INSERT INTO `chat_message` VALUES (43, '866818', 5, 9, 4, 'hello', '', '[]', 0, '2026-09-20 03:17:26');
INSERT INTO `chat_message` VALUES (44, '866818', 5, 10, 4, 'hello', '', '[]', 0, '2026-09-20 03:17:26');
INSERT INTO `chat_message` VALUES (45, '866818', 5, NULL, 3, 'hello', '', '[]', 0, '2026-09-20 03:17:26');
INSERT INTO `chat_message` VALUES (46, '866818', 5, NULL, 4, 'hello', '', '[]', 0, '2026-09-20 03:20:39');
INSERT INTO `chat_message` VALUES (47, '866818', 5, 11, 4, 'hello', '', '[]', 0, '2026-09-20 03:20:39');
INSERT INTO `chat_message` VALUES (48, '866818', 5, NULL, 3, 'hello', '', '[]', 0, '2026-09-20 03:20:40');
INSERT INTO `chat_message` VALUES (49, '866818', 5, 12, 4, 'What is the weather like in Beijing today?', '北京今天的天气为阴，当前气温为22.6°C，湿度为76%，风速为5.1 km/h。', '[]', 0, '2026-09-20 03:40:20');
INSERT INTO `chat_message` VALUES (50, '866818', 5, 13, 4, 'What is the weather like in Beijing today?', '北京今天的天气为阴，当前气温为22.6°C，湿度为76%，风速为5.1 km/h。', '[]', 0, '2026-09-20 03:41:01');
INSERT INTO `chat_message` VALUES (51, '866818', 5, 14, 4, 'What is the weather like in Beijing today?', '北京今天的天气为阴，当前气温为22.6°C，湿度为76%，风速为5.1 km/h。', '[]', 0, '2026-09-20 03:41:39');
INSERT INTO `chat_message` VALUES (52, '705879', 3, 15, 3, '衡阳未来7天天气如何', '衡阳未来7天天气预报如下：\n\n- **9月20日**：毛毛雨，气温 20.0°C ~ 28.9°C，降水概率 71%  \n- **9月21日**：雷暴，气温 20.2°C ~ 27.8°C，降水概率 96%  \n- **9月22日**：雷暴，气温 19.1°C ~ 25.8°C，降水概率 92%  \n- **9月23日**：毛毛雨，气温 18.2°C ~ 25.6°C，降水概率 47%  \n- **9月24日**：多云，气温 16.9°C ~ 27.0°C，降水概率 12%  \n- **9月25日**：阴，气温 16.4°C ~ 27.1°C，降水概率 6%  \n- **9月26日**：毛毛雨，气温 19.1°C ~ 28.9°C，降水概率 12%  \n\n温馨提示：21–22日有高概率雷暴，建议关注临近预警，出行注意防雷防雨；24–25日天气较稳定，适宜户外活动。', '[]', 0, '2026-09-20 04:35:51');
INSERT INTO `chat_message` VALUES (53, '705879', 2, 16, 3, '什么是回表', '回表是指在 MySQL 的 InnoDB 存储引擎中，当使用**二级索引**（非聚簇索引）进行查询时，先通过该二级索引找到对应记录的**主键值**，再根据该主键值**回到聚簇索引（主键索引）中查找完整行数据**的过程 [1]。\n\n由于二级索引的叶子节点仅存储**索引字段 + 主键值**，不包含其他列数据（如 `age`、`phone` 等）[2]，因此若 `SELECT` 语句需要的字段未全部被二级索引覆盖，就必须额外进行一次基于主键的聚簇索引查找，即“回表”。\n\n例如：\n```sql\nSELECT * FROM user WHERE name = \'张三\';\n```\n执行流程为：\n- 在 `idx_name` 二级索引中查到 `name = \'张三\'` 对应的主键 `id = 100`；\n- 再用 `id = 100` 去聚簇索引中检索整行数据；\n- 这第二次访问主键索引的操作，就是**回表** [3]。\n\n若查询字段全部包含在二级索引中（如 `SELECT id, name FROM user WHERE name = \'张三\'`），则无需回表，称为**覆盖索引** [4]。', '[{\"seq\":1,\"chunkId\":1128,\"documentId\":10,\"documentName\":\"17092026_MySQL 索引与 Java HashMap 速记笔记.md\",\"page\":0,\"previewUrl\":\"http://192.168.1.200:9000/rag-files/705879/3/2026-09-20/669ebccf-11f6-41fa-9ea0-d7a1e547f527.md?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260920%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260920T143852Z&X-Amz-Expires=900&X-Amz-SignedHeaders=host&X-Amz-Signature=f0e22032f129a27e59d96d75edc5d7eb5ff4a66e3f17e0855333bd36e061623b\",\"content\":\"InnoDB 中：\\n> 先通过二级索引找到主键，再通过主键去聚簇索引查询完整记录，这个过程叫回表\\n例如：\\n```sql\\nCREATE TABLE user (\\n    id BIGINT PRIMARY KEY,\\n    name VARCHAR(50),\\n    age INT,\\n    phone VARCHAR(20),\\n    INDEX idx_name(name)\\n);\\n```\\n执行：\\n```sql\\nSELECT *\\nFROM user\\nWHERE name = \'张三\';\\n```\\n流程：\\n```text\\nidx_name 二级索引\\n↓\\n找到 name = 张三\\n↓\\n得到主键 id = 100\\n↓\\n根据 id = 100\\n↓\\n查询主键索引\\n↓\\n获取完整行\\n```\\n第二次根据主键查完整记录：\\n> 回表\\n---\",\"rerankScore\":null},{\"seq\":2,\"chunkId\":1129,\"documentId\":10,\"documentName\":\"17092026_MySQL 索引与 Java HashMap 速记笔记.md\",\"page\":0,\"previewUrl\":\"http://192.168.1.200:9000/rag-files/705879/3/2026-09-20/669ebccf-11f6-41fa-9ea0-d7a1e547f527.md?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260920%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260920T143852Z&X-Amz-Expires=900&X-Amz-SignedHeaders=host&X-Amz-Signature=f0e22032f129a27e59d96d75edc5d7eb5ff4a66e3f17e0855333bd36e061623b\",\"content\":\"二级索引叶子节点一般保存：\\n```text\\n索引字段\\n+\\n主键值\\n```\\n例如：\\n```text\\nzhangsan -> id = 100\\n```\\n但没有：\\n```text\\nage\\nphone\\n其他字段\\n```\\n所以如果查询：\\n```sql\\nSELECT age, phone\\nFROM user\\nWHERE name = \'zhangsan\';\\n```\\n必须根据 `id=100` 再去主键索引查询\\n---\",\"rerankScore\":null},{\"seq\":3,\"chunkId\":1146,\"documentId\":10,\"documentName\":\"17092026_MySQL 索引与 Java HashMap 速记笔记.md\",\"page\":0,\"previewUrl\":\"http://192.168.1.200:9000/rag-files/705879/3/2026-09-20/669ebccf-11f6-41fa-9ea0-d7a1e547f527.md?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260920%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260920T143852Z&X-Amz-Expires=900&X-Amz-SignedHeaders=host&X-Amz-Signature=f0e22032f129a27e59d96d75edc5d7eb5ff4a66e3f17e0855333bd36e061623b\",\"content\":\"> 二级索引查到主键以后，再通过主键去聚簇索引查询完整数据，这个过程叫回表\",\"rerankScore\":null},{\"seq\":4,\"chunkId\":1130,\"documentId\":10,\"documentName\":\"17092026_MySQL 索引与 Java HashMap 速记笔记.md\",\"page\":0,\"previewUrl\":\"http://192.168.1.200:9000/rag-files/705879/3/2026-09-20/669ebccf-11f6-41fa-9ea0-d7a1e547f527.md?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260920%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260920T143852Z&X-Amz-Expires=900&X-Amz-SignedHeaders=host&X-Amz-Signature=f0e22032f129a27e59d96d75edc5d7eb5ff4a66e3f17e0855333bd36e061623b\",\"content\":\"如果查询需要的数据全部存在于索引中：\\n```sql\\nSELECT id, name\\nFROM user\\nWHERE name = \'zhangsan\';\\n```\\n二级索引已经包含：\\n```text\\nname\\nid\\n```\\n就不需要回表\\n这叫：\\n> 覆盖索引\\n记忆：\\n```text\\n索引能提供全部字段\\n↓\\n不用回表\\n↓\\n覆盖索引\\n```\\n---\",\"rerankScore\":null},{\"seq\":5,\"chunkId\":1137,\"documentId\":10,\"documentName\":\"17092026_MySQL 索引与 Java HashMap 速记笔记.md\",\"page\":0,\"previewUrl\":\"http://192.168.1.200:9000/rag-files/705879/3/2026-09-20/669ebccf-11f6-41fa-9ea0-d7a1e547f527.md?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260920%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260920T143852Z&X-Amz-Expires=900&X-Amz-SignedHeaders=host&X-Amz-Signature=f0e22032f129a27e59d96d75edc5d7eb5ff4a66e3f17e0855333bd36e061623b\",\"content\":\"常记：\\n```text\\n节点数量 <= 6\\n```\\n红黑树可能退化为链表\\n尤其是在：\\n> HashMap resize 扩容以后，红黑树桶拆分时\\n如果拆分后的某一边：\\n```text\\n节点数 <= 6\\n```\\n就会执行：\\n```text\\n红黑树\\n↓\\n链表\\n```\\n这个过程叫：\\n> untreeify\\n---\",\"rerankScore\":null}]', 1010, '2026-09-20 22:38:58');
INSERT INTO `chat_message` VALUES (54, '705879', 2, 16, 3, '索引是什么', '索引是数据库为了**快速定位数据**而建立的一种**有序数据结构**，其本质是**用额外的存储空间，换取更快的查询速度**（即“以空间换时间”）[1][3]。\n\n类比为书籍的目录：  \n- **没有索引**时，数据库需从第一条记录开始逐行扫描，直到找到目标数据；  \n- **有索引**时，则先查找索引（如 B+Tree），快速定位到数据所在位置，再直接访问对应记录 [1]。\n\n在 MySQL InnoDB 中，索引通常基于 **B+Tree** 实现——一种多叉、平衡、有序的树形结构，支持高效范围查询与等值查询 [2]。  \n索引分为：\n- **聚簇索引**（如主键索引）：叶子节点存储完整的行数据；\n- **二级索引**（如普通字段索引）：叶子节点仅存储索引列 + 主键值，查询非覆盖字段时需回表 [4][5]。\n\n因此，索引不是免费的“加速器”，它会占用额外磁盘空间，并在插入、更新、删除时带来维护开销，需权衡使用 [3]。', '[{\"seq\":1,\"chunkId\":1118,\"documentId\":10,\"documentName\":\"17092026_MySQL 索引与 Java HashMap 速记笔记.md\",\"page\":0,\"previewUrl\":\"http://192.168.1.200:9000/rag-files/705879/3/2026-09-20/669ebccf-11f6-41fa-9ea0-d7a1e547f527.md?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260920%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260920T144030Z&X-Amz-Expires=900&X-Amz-SignedHeaders=host&X-Amz-Signature=5b5bedadff8a5aee54de36aba68e949d85f8eb28172ac305767d9870db54df19\",\"content\":\"索引可以理解为数据库中的“目录”\\n没有索引：\\n```text\\n从第一条数据开始扫描\\n↓\\n一直找到目标数据\\n```\\n有索引：\\n```text\\n先查索引\\n↓\\n快速定位数据\\n```\\n核心作用：\\n> 用额外的存储空间，换取更快的查询速度\\n---\",\"rerankScore\":null},{\"seq\":2,\"chunkId\":1123,\"documentId\":10,\"documentName\":\"17092026_MySQL 索引与 Java HashMap 速记笔记.md\",\"page\":0,\"previewUrl\":\"http://192.168.1.200:9000/rag-files/705879/3/2026-09-20/669ebccf-11f6-41fa-9ea0-d7a1e547f527.md?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260920%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260920T144030Z&X-Amz-Expires=900&X-Amz-SignedHeaders=host&X-Amz-Signature=5b5bedadff8a5aee54de36aba68e949d85f8eb28172ac305767d9870db54df19\",\"content\":\"B+Tree 是一种：\\n```text\\n多叉\\n平衡\\n有序\\n树形数据结构\\n```\\nMySQL InnoDB 索引大量使用 B+Tree\\n简单结构：\\n```text\\n             [20 | 40]\\n            /    |    \\\\\\n           /     |     \\\\\\n     [1 5 8] [20 25 30] [40 50]\\n```\\n查找 `25`：\\n```text\\n根节点\\n↓\\n20 < 25 < 40\\n↓\\n进入中间节点\\n↓\\n找到 25\\n```\\n---\",\"rerankScore\":null},{\"seq\":3,\"chunkId\":1144,\"documentId\":10,\"documentName\":\"17092026_MySQL 索引与 Java HashMap 速记笔记.md\",\"page\":0,\"previewUrl\":\"http://192.168.1.200:9000/rag-files/705879/3/2026-09-20/669ebccf-11f6-41fa-9ea0-d7a1e547f527.md?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260920%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260920T144030Z&X-Amz-Expires=900&X-Amz-SignedHeaders=host&X-Amz-Signature=5b5bedadff8a5aee54de36aba68e949d85f8eb28172ac305767d9870db54df19\",\"content\":\"> 索引是数据库为了快速定位数据而建立的一种有序数据结构，本质上是用空间换查询效率\",\"rerankScore\":null},{\"seq\":4,\"chunkId\":1128,\"documentId\":10,\"documentName\":\"17092026_MySQL 索引与 Java HashMap 速记笔记.md\",\"page\":0,\"previewUrl\":\"http://192.168.1.200:9000/rag-files/705879/3/2026-09-20/669ebccf-11f6-41fa-9ea0-d7a1e547f527.md?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260920%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260920T144030Z&X-Amz-Expires=900&X-Amz-SignedHeaders=host&X-Amz-Signature=5b5bedadff8a5aee54de36aba68e949d85f8eb28172ac305767d9870db54df19\",\"content\":\"InnoDB 中：\\n> 先通过二级索引找到主键，再通过主键去聚簇索引查询完整记录，这个过程叫回表\\n例如：\\n```sql\\nCREATE TABLE user (\\n    id BIGINT PRIMARY KEY,\\n    name VARCHAR(50),\\n    age INT,\\n    phone VARCHAR(20),\\n    INDEX idx_name(name)\\n);\\n```\\n执行：\\n```sql\\nSELECT *\\nFROM user\\nWHERE name = \'张三\';\\n```\\n流程：\\n```text\\nidx_name 二级索引\\n↓\\n找到 name = 张三\\n↓\\n得到主键 id = 100\\n↓\\n根据 id = 100\\n↓\\n查询主键索引\\n↓\\n获取完整行\\n```\\n第二次根据主键查完整记录：\\n> 回表\\n---\",\"rerankScore\":null},{\"seq\":5,\"chunkId\":1129,\"documentId\":10,\"documentName\":\"17092026_MySQL 索引与 Java HashMap 速记笔记.md\",\"page\":0,\"previewUrl\":\"http://192.168.1.200:9000/rag-files/705879/3/2026-09-20/669ebccf-11f6-41fa-9ea0-d7a1e547f527.md?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260920%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260920T144030Z&X-Amz-Expires=900&X-Amz-SignedHeaders=host&X-Amz-Signature=5b5bedadff8a5aee54de36aba68e949d85f8eb28172ac305767d9870db54df19\",\"content\":\"二级索引叶子节点一般保存：\\n```text\\n索引字段\\n+\\n主键值\\n```\\n例如：\\n```text\\nzhangsan -> id = 100\\n```\\n但没有：\\n```text\\nage\\nphone\\n其他字段\\n```\\n所以如果查询：\\n```sql\\nSELECT age, phone\\nFROM user\\nWHERE name = \'zhangsan\';\\n```\\n必须根据 `id=100` 再去主键索引查询\\n---\",\"rerankScore\":null}]', 1308, '2026-09-20 22:40:45');
INSERT INTO `chat_message` VALUES (55, '705879', 2, 16, 3, '衡阳未来7天天气', '衡阳未来7天天气预报如下：\n\n- **2026-09-20**：毛毛雨，最高29.9°C，最低20.1°C，降水概率76%  \n- **2026-09-21**：雷暴，最高27.7°C，最低20.2°C，降水概率98%  \n- **2026-09-22**：雷暴，最高26.7°C，最低19.4°C，降水概率100%  \n- **2026-09-23**：雷暴，最高25.2°C，最低19.0°C，降水概率88%  \n- **2026-09-24**：阴，最高26.3°C，最低17.2°C，降水概率22%  \n- **2026-09-25**：阴，最高27.0°C，最低17.4°C，降水概率6%  \n- **2026-09-26**：阴，最高30.1°C，最低18.8°C，降水概率24%  \n\n请注意防范强降水及雷电天气，合理安排出行[1]。', '[]', 0, '2026-09-20 22:41:41');
INSERT INTO `chat_message` VALUES (56, '705879', 2, 16, 3, '普通索引是什么', '普通索引（Normal Index）是 MySQL 中最基础、最常用的索引类型，其核心作用是**提升查询效率**，**不约束数据的唯一性** [1]。\n\n具体特点如下：\n- ✅ **允许重复值**：字段中可以存在相同内容（如多个用户的 `name` 都为“张三”）；  \n- ✅ **可创建多个**：一张表可建立多个普通索引，分别针对不同字段或字段组合；  \n- ✅ **仅用于加速查询**：不参与数据完整性校验，不影响 `INSERT`/`UPDATE` 的唯一性检查；  \n- ✅ **支持单列或联合形式**：如单列索引 `INDEX idx_name(name)`，或联合索引 `INDEX idx_status_time(status, create_time)` [2]。\n\n示例语句：\n```sql\nCREATE INDEX idx_email ON user(email);  -- 单列普通索引\nCREATE INDEX idx_city_age ON user(city, age);  -- 联合普通索引\n```\n\n需注意：普通索引属于**二级索引**，查询时若未覆盖所有所需字段，仍可能触发**回表**操作 [4]；且遵循**最左匹配原则**（联合索引中，查询条件必须包含最左前缀才能生效）[5]。\n\n简言之：普通索引 = “纯提速工具”，无业务约束力，但对查询性能至关重要 [1]。', '[{\"seq\":1,\"chunkId\":1119,\"documentId\":10,\"documentName\":\"17092026_MySQL 索引与 Java HashMap 速记笔记.md\",\"page\":0,\"previewUrl\":\"http://192.168.1.200:9000/rag-files/705879/3/2026-09-20/669ebccf-11f6-41fa-9ea0-d7a1e547f527.md?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260920%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260920T144656Z&X-Amz-Expires=900&X-Amz-SignedHeaders=host&X-Amz-Signature=cb0a2ef2d0664205e6db43c3c90f0cadc0535772d1d54cd7feb050393c56019c\",\"content\":\"主要作用：\\n> 提高查询效率，不限制数据重复\\n```sql\\nCREATE INDEX idx_name ON user(name);\\n```\\n特点：\\n- 可以重复\\n- 可以有多个普通索引\\n- 主要用于查询优化\\n---\",\"rerankScore\":null},{\"seq\":2,\"chunkId\":1121,\"documentId\":10,\"documentName\":\"17092026_MySQL 索引与 Java HashMap 速记笔记.md\",\"page\":0,\"previewUrl\":\"http://192.168.1.200:9000/rag-files/705879/3/2026-09-20/669ebccf-11f6-41fa-9ea0-d7a1e547f527.md?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260920%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260920T144656Z&X-Amz-Expires=900&X-Amz-SignedHeaders=host&X-Amz-Signature=cb0a2ef2d0664205e6db43c3c90f0cadc0535772d1d54cd7feb050393c56019c\",\"content\":\"多个字段组成一个索引\\n```sql\\nCREATE INDEX idx_status_time\\nON orders(status, create_time);\\n```\\n例如：\\n```text\\n(status, create_time)\\n```\\n是一个整体索引\\n联合索引可以是：\\n```text\\n普通联合索引\\n唯一联合索引\\n```\\n例如：\\n```sql\\nUNIQUE KEY uk_tenant_username(tenant_id, username);\\n```\\n表示：\\n> 同一个租户内 username 唯一\\n但不同租户允许相同 username\\n---\",\"rerankScore\":null},{\"seq\":3,\"chunkId\":1118,\"documentId\":10,\"documentName\":\"17092026_MySQL 索引与 Java HashMap 速记笔记.md\",\"page\":0,\"previewUrl\":\"http://192.168.1.200:9000/rag-files/705879/3/2026-09-20/669ebccf-11f6-41fa-9ea0-d7a1e547f527.md?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260920%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260920T144656Z&X-Amz-Expires=900&X-Amz-SignedHeaders=host&X-Amz-Signature=cb0a2ef2d0664205e6db43c3c90f0cadc0535772d1d54cd7feb050393c56019c\",\"content\":\"索引可以理解为数据库中的“目录”\\n没有索引：\\n```text\\n从第一条数据开始扫描\\n↓\\n一直找到目标数据\\n```\\n有索引：\\n```text\\n先查索引\\n↓\\n快速定位数据\\n```\\n核心作用：\\n> 用额外的存储空间，换取更快的查询速度\\n---\",\"rerankScore\":null},{\"seq\":4,\"chunkId\":1128,\"documentId\":10,\"documentName\":\"17092026_MySQL 索引与 Java HashMap 速记笔记.md\",\"page\":0,\"previewUrl\":\"http://192.168.1.200:9000/rag-files/705879/3/2026-09-20/669ebccf-11f6-41fa-9ea0-d7a1e547f527.md?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260920%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260920T144656Z&X-Amz-Expires=900&X-Amz-SignedHeaders=host&X-Amz-Signature=cb0a2ef2d0664205e6db43c3c90f0cadc0535772d1d54cd7feb050393c56019c\",\"content\":\"InnoDB 中：\\n> 先通过二级索引找到主键，再通过主键去聚簇索引查询完整记录，这个过程叫回表\\n例如：\\n```sql\\nCREATE TABLE user (\\n    id BIGINT PRIMARY KEY,\\n    name VARCHAR(50),\\n    age INT,\\n    phone VARCHAR(20),\\n    INDEX idx_name(name)\\n);\\n```\\n执行：\\n```sql\\nSELECT *\\nFROM user\\nWHERE name = \'张三\';\\n```\\n流程：\\n```text\\nidx_name 二级索引\\n↓\\n找到 name = 张三\\n↓\\n得到主键 id = 100\\n↓\\n根据 id = 100\\n↓\\n查询主键索引\\n↓\\n获取完整行\\n```\\n第二次根据主键查完整记录：\\n> 回表\\n---\",\"rerankScore\":null},{\"seq\":5,\"chunkId\":1151,\"documentId\":10,\"documentName\":\"17092026_MySQL 索引与 Java HashMap 速记笔记.md\",\"page\":0,\"previewUrl\":\"http://192.168.1.200:9000/rag-files/705879/3/2026-09-20/669ebccf-11f6-41fa-9ea0-d7a1e547f527.md?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260920%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260920T144656Z&X-Amz-Expires=900&X-Amz-SignedHeaders=host&X-Amz-Signature=cb0a2ef2d0664205e6db43c3c90f0cadc0535772d1d54cd7feb050393c56019c\",\"content\":\"```text\\n索引 = 数据库目录\\n\\n普通索引 = 提速\\n\\n唯一索引 = 提速 + 防重复\\n\\n联合索引 = 多字段索引\\n\\n联合索引 = 最左匹配\\n\\nB+Tree = 多叉 + 平衡 + 有序 + 叶子链表\\n\\n回表 = 二级索引 → 主键 → 完整数据\\n\\n覆盖索引 = 不回表\\n\\nHashMap = 数组 + 链表 + 红黑树\\n\\nHashMap = hash → 桶 → 查找\\n\\n8 树化\\n6 退化\\n64 才树化\\n\\n负载因子 = 0.75\\n\\n扩容 = 通常 2 倍\\n\\nget / put 平均 = O(1)\\n\\n链表最坏 = O(n)\\n\\n红黑树 = O(log n)\\n```\",\"rerankScore\":null}]', 2021, '2026-09-20 22:47:03');

-- ----------------------------
-- Table structure for chat_session
-- ----------------------------
CREATE TABLE `chat_session`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `user_id` bigint NOT NULL,
  `kb_id` bigint NOT NULL,
  `title` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_tenant_user_updated`(`tenant_id` ASC, `user_id` ASC, `updated_at` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 17 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of chat_session
-- ----------------------------
INSERT INTO `chat_session` VALUES (3, '705879', 3, 3, 'MySQL 一条 SQL 是怎么执行的', '2026-09-20 02:33:24', '2026-09-20 02:35:44');
INSERT INTO `chat_session` VALUES (4, '705879', 2, 3, '衡阳明天天气如何', '2026-09-20 02:39:24', '2026-09-20 02:43:03');
INSERT INTO `chat_session` VALUES (5, '705879', 2, 3, '衡阳近7天天气如何', '2026-09-20 02:58:14', '2026-09-20 02:58:23');
INSERT INTO `chat_session` VALUES (6, '866818', 5, 4, 'What is the weather like in Beijing toda', '2026-09-20 03:15:53', '2026-09-20 03:15:54');
INSERT INTO `chat_session` VALUES (7, '866818', 5, 4, 'What is the weather like in Beijing toda', '2026-09-20 03:15:55', '2026-09-20 03:15:57');
INSERT INTO `chat_session` VALUES (8, '866818', 5, 4, 'What is the weather like in Shanghai tod', '2026-09-20 03:15:59', '2026-09-20 03:15:59');
INSERT INTO `chat_session` VALUES (9, '866818', 5, 4, 'hello', '2026-09-20 03:17:26', '2026-09-20 03:17:25');
INSERT INTO `chat_session` VALUES (10, '866818', 5, 4, 'hello', '2026-09-20 03:17:26', '2026-09-20 03:17:25');
INSERT INTO `chat_session` VALUES (11, '866818', 5, 4, 'hello', '2026-09-20 03:20:39', '2026-09-20 03:20:38');
INSERT INTO `chat_session` VALUES (12, '866818', 5, 4, 'What is the weather like in Beijing toda', '2026-09-20 03:40:15', '2026-09-20 03:40:18');
INSERT INTO `chat_session` VALUES (13, '866818', 5, 4, 'What is the weather like in Beijing toda', '2026-09-20 03:40:59', '2026-09-20 03:41:00');
INSERT INTO `chat_session` VALUES (14, '866818', 5, 4, 'What is the weather like in Beijing toda', '2026-09-20 03:41:37', '2026-09-20 03:41:38');
INSERT INTO `chat_session` VALUES (15, '705879', 3, 3, '衡阳未来7天天气如何', '2026-09-20 04:35:41', '2026-09-20 04:35:50');
INSERT INTO `chat_session` VALUES (16, '705879', 2, 3, '什么是回表', '2026-09-20 22:38:41', '2026-09-20 22:47:01');

-- ----------------------------
-- Table structure for chunk
-- ----------------------------
CREATE TABLE `chunk`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `kb_id` bigint NOT NULL,
  `document_id` bigint NOT NULL,
  `tenant_id` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `seq` int NOT NULL DEFAULT 0 COMMENT '文档内切片序号',
  `content` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `page` int NOT NULL DEFAULT 0,
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'AUTO' COMMENT 'AUTO自动/MANUAL人工/DELETED已删除',
  `chunk_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'CHILD' COMMENT 'PARENT/CHILD',
  `content_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'TEXT' COMMENT 'TEXT/TABLE/IMAGE/CODE',
  `parent_chunk_id` bigint NULL DEFAULT NULL COMMENT '所属 parent 切片 id',
  `section_title` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '所属章节（叶子标题）',
  `section_path` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '章节完整路径，用 > 连接',
  `content_hash` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'SHA256(documentId+sectionPath+normalizedContent)',
  `table_json` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '表格 JSON（contentType=TABLE 时）',
  `table_summary` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '表格自然语言摘要',
  `image_url` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '图片来源 URL',
  `image_ocr` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '图片 OCR 文本',
  `image_vision` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '图片 Vision 描述',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_doc`(`document_id` ASC) USING BTREE,
  INDEX `idx_kb`(`kb_id` ASC) USING BTREE,
  INDEX `idx_tenant`(`tenant_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1152 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '切片' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of chunk
-- ----------------------------
INSERT INTO `chunk` VALUES (1118, 3, 10, '705879', 1, '索引可以理解为数据库中的“目录”\n没有索引：\n```text\n从第一条数据开始扫描\n↓\n一直找到目标数据\n```\n有索引：\n```text\n先查索引\n↓\n快速定位数据\n```\n核心作用：\n> 用额外的存储空间，换取更快的查询速度\n---', 0, 'AUTO', 'CHILD', 'TEXT', NULL, '1. 索引是什么', 'MySQL 索引与 Java HashMap 速记笔记 > 一、MySQL 索引 > 1. 索引是什么', NULL, NULL, NULL, NULL, NULL, NULL, '2026-09-20 22:37:16', '2026-09-20 22:37:16');
INSERT INTO `chunk` VALUES (1119, 3, 10, '705879', 2, '主要作用：\n> 提高查询效率，不限制数据重复\n```sql\nCREATE INDEX idx_name ON user(name);\n```\n特点：\n- 可以重复\n- 可以有多个普通索引\n- 主要用于查询优化\n---', 0, 'AUTO', 'CHILD', 'TEXT', NULL, '1. 普通索引', 'MySQL 索引与 Java HashMap 速记笔记 > 二、常见索引类型 > 1. 普通索引', NULL, NULL, NULL, NULL, NULL, NULL, '2026-09-20 22:37:16', '2026-09-20 22:37:16');
INSERT INTO `chunk` VALUES (1120, 3, 10, '705879', 3, '作用：\n> 查询加速 + 保证字段值唯一\n```sql\nCREATE UNIQUE INDEX uk_phone ON user(phone);\n```\n例如：\n```text\n13800138000\n13800138000\n```\n第二条插入会失败\n常见场景：\n```text\n手机号\n邮箱\n用户名\n订单号\n支付流水号\nbiz_no\n```\n注意：\nMySQL 唯一索引通常允许多个 `NULL`\n---', 0, 'AUTO', 'CHILD', 'TEXT', NULL, '2. 唯一索引', 'MySQL 索引与 Java HashMap 速记笔记 > 二、常见索引类型 > 2. 唯一索引', NULL, NULL, NULL, NULL, NULL, NULL, '2026-09-20 22:37:16', '2026-09-20 22:37:16');
INSERT INTO `chunk` VALUES (1121, 3, 10, '705879', 4, '多个字段组成一个索引\n```sql\nCREATE INDEX idx_status_time\nON orders(status, create_time);\n```\n例如：\n```text\n(status, create_time)\n```\n是一个整体索引\n联合索引可以是：\n```text\n普通联合索引\n唯一联合索引\n```\n例如：\n```sql\nUNIQUE KEY uk_tenant_username(tenant_id, username);\n```\n表示：\n> 同一个租户内 username 唯一\n但不同租户允许相同 username\n---', 0, 'AUTO', 'CHILD', 'TEXT', NULL, '3. 联合索引', 'MySQL 索引与 Java HashMap 速记笔记 > 二、常见索引类型 > 3. 联合索引', NULL, NULL, NULL, NULL, NULL, NULL, '2026-09-20 22:37:16', '2026-09-20 22:37:16');
INSERT INTO `chunk` VALUES (1122, 3, 10, '705879', 5, '假设：\n```sql\nINDEX idx_a_b_c(a, b, c);\n```\n可以很好使用索引：\n```sql\nWHERE a = 1;\n```\n```sql\nWHERE a = 1 AND b = 2;\n```\n```sql\nWHERE a = 1 AND b = 2 AND c = 3;\n```\n通常不能充分利用该联合索引：\n```sql\nWHERE b = 2;\n```\n```sql\nWHERE b = 2 AND c = 3;\n```\n原因：\nB+Tree 是按照：\n```text\na\n↓\nb\n↓\nc\n```\n顺序组织的\n口诀：\n> 联合索引，从最左边开始匹配\n---', 0, 'AUTO', 'CHILD', 'TEXT', NULL, '三、最左前缀原则', 'MySQL 索引与 Java HashMap 速记笔记 > 三、最左前缀原则', NULL, NULL, NULL, NULL, NULL, NULL, '2026-09-20 22:37:16', '2026-09-20 22:37:16');
INSERT INTO `chunk` VALUES (1123, 3, 10, '705879', 6, 'B+Tree 是一种：\n```text\n多叉\n平衡\n有序\n树形数据结构\n```\nMySQL InnoDB 索引大量使用 B+Tree\n简单结构：\n```text\n             [20 | 40]\n            /    |    \\\n           /     |     \\\n     [1 5 8] [20 25 30] [40 50]\n```\n查找 `25`：\n```text\n根节点\n↓\n20 < 25 < 40\n↓\n进入中间节点\n↓\n找到 25\n```\n---', 0, 'AUTO', 'CHILD', 'TEXT', NULL, '1. B+Tree 是什么', '四、B+Tree > 1. B+Tree 是什么', NULL, NULL, NULL, NULL, NULL, NULL, '2026-09-20 22:37:16', '2026-09-20 22:37:16');
INSERT INTO `chunk` VALUES (1124, 3, 10, '705879', 7, '一个节点可以有很多子节点\n优点：\n```text\n分叉多\n↓\n树高度低\n↓\n磁盘 IO 少\n```\n---', 0, 'AUTO', 'CHILD', 'TEXT', NULL, '多叉', '四、B+Tree > 2. B+Tree 的特点 > 多叉', NULL, NULL, NULL, NULL, NULL, NULL, '2026-09-20 22:37:16', '2026-09-20 22:37:16');
INSERT INTO `chunk` VALUES (1125, 3, 10, '705879', 8, '非叶子节点主要负责：\n```text\n导航\n```\n叶子节点存：\n```text\n真正的数据 / 数据指针\n```\n---', 0, 'AUTO', 'CHILD', 'TEXT', NULL, '数据主要在叶子节点', '四、B+Tree > 2. B+Tree 的特点 > 数据主要在叶子节点', NULL, NULL, NULL, NULL, NULL, NULL, '2026-09-20 22:37:16', '2026-09-20 22:37:16');
INSERT INTO `chunk` VALUES (1126, 3, 10, '705879', 9, '例如：\n```text\n[1,5,8]\n   ↓\n[20,25,30]\n   ↓\n[40,50]\n```\n所以特别适合：\n```sql\nWHERE id > 100;\n\nWHERE id BETWEEN 100 AND 200;\n\nORDER BY id;\n```\n即：\n> 范围查询性能很好\n---', 0, 'AUTO', 'CHILD', 'TEXT', NULL, '叶子节点有序连接', '四、B+Tree > 2. B+Tree 的特点 > 叶子节点有序连接', NULL, NULL, NULL, NULL, NULL, NULL, '2026-09-20 22:37:16', '2026-09-20 22:37:16');
INSERT INTO `chunk` VALUES (1127, 3, 10, '705879', 10, '核心原因：\n```text\n树矮\n↓\n减少磁盘 IO\n\n叶子节点连续\n↓\n适合范围查询\n\n有序\n↓\n适合排序\n```\n面试回答：\n> B+Tree 分叉多、树高度低，可以减少磁盘 IO，同时叶子节点之间有序连接，非常适合等值查询、范围查询和排序\n---', 0, 'AUTO', 'CHILD', 'TEXT', NULL, '3. 为什么数据库喜欢 B+Tree', '四、B+Tree > 3. 为什么数据库喜欢 B+Tree', NULL, NULL, NULL, NULL, NULL, NULL, '2026-09-20 22:37:16', '2026-09-20 22:37:16');
INSERT INTO `chunk` VALUES (1128, 3, 10, '705879', 11, 'InnoDB 中：\n> 先通过二级索引找到主键，再通过主键去聚簇索引查询完整记录，这个过程叫回表\n例如：\n```sql\nCREATE TABLE user (\n    id BIGINT PRIMARY KEY,\n    name VARCHAR(50),\n    age INT,\n    phone VARCHAR(20),\n    INDEX idx_name(name)\n);\n```\n执行：\n```sql\nSELECT *\nFROM user\nWHERE name = \'张三\';\n```\n流程：\n```text\nidx_name 二级索引\n↓\n找到 name = 张三\n↓\n得到主键 id = 100\n↓\n根据 id = 100\n↓\n查询主键索引\n↓\n获取完整行\n```\n第二次根据主键查完整记录：\n> 回表\n---', 0, 'AUTO', 'CHILD', 'TEXT', NULL, '1. 什么是回表', '五、回表 > 1. 什么是回表', NULL, NULL, NULL, NULL, NULL, NULL, '2026-09-20 22:37:16', '2026-09-20 22:37:16');
INSERT INTO `chunk` VALUES (1129, 3, 10, '705879', 12, '二级索引叶子节点一般保存：\n```text\n索引字段\n+\n主键值\n```\n例如：\n```text\nzhangsan -> id = 100\n```\n但没有：\n```text\nage\nphone\n其他字段\n```\n所以如果查询：\n```sql\nSELECT age, phone\nFROM user\nWHERE name = \'zhangsan\';\n```\n必须根据 `id=100` 再去主键索引查询\n---', 0, 'AUTO', 'CHILD', 'TEXT', NULL, '2. 为什么会回表', '五、回表 > 2. 为什么会回表', NULL, NULL, NULL, NULL, NULL, NULL, '2026-09-20 22:37:16', '2026-09-20 22:37:16');
INSERT INTO `chunk` VALUES (1130, 3, 10, '705879', 13, '如果查询需要的数据全部存在于索引中：\n```sql\nSELECT id, name\nFROM user\nWHERE name = \'zhangsan\';\n```\n二级索引已经包含：\n```text\nname\nid\n```\n就不需要回表\n这叫：\n> 覆盖索引\n记忆：\n```text\n索引能提供全部字段\n↓\n不用回表\n↓\n覆盖索引\n```\n---', 0, 'AUTO', 'CHILD', 'TEXT', NULL, '六、覆盖索引', '六、覆盖索引', NULL, NULL, NULL, NULL, NULL, NULL, '2026-09-20 22:37:16', '2026-09-20 22:37:16');
INSERT INTO `chunk` VALUES (1131, 3, 10, '705879', 14, 'JDK 8+：\n> 数组 + 链表 + 红黑树\n结构：\n```text\nNode[] table\n│\n├── bucket 0\n│\n├── bucket 1 -> Node -> Node\n│\n├── bucket 2 -> Node\n│\n└── bucket 3 -> 红黑树\n```\n数组中的每个位置叫：\n> bucket，桶\n---', 0, 'AUTO', 'CHILD', 'TEXT', NULL, '1. HashMap 底层数据结构', '七、Java HashMap > 1. HashMap 底层数据结构', NULL, NULL, NULL, NULL, NULL, NULL, '2026-09-20 22:37:16', '2026-09-20 22:37:16');
INSERT INTO `chunk` VALUES (1132, 3, 10, '705879', 15, '```java\nmap.put(\"zhangsan\", 18);\n```\n流程：\n```text\nkey\n↓\nhashCode()\n↓\n扰动计算\n↓\n计算数组下标\n↓\ntable[index]\n```\n核心公式：\n```java\nindex = (n - 1) & hash;\n```\n其中：\n```text\nn = 数组长度\nhash = key 处理后的 hash 值\n```\n---', 0, 'AUTO', 'CHILD', 'TEXT', NULL, '2. HashMap 如何定位数据', '七、Java HashMap > 2. HashMap 如何定位数据', NULL, NULL, NULL, NULL, NULL, NULL, '2026-09-20 22:37:16', '2026-09-20 22:37:16');
INSERT INTO `chunk` VALUES (1133, 3, 10, '705879', 16, '不同 key 可能计算到相同数组下标：\n```text\nA -> index 5\nB -> index 5\nC -> index 5\n```\n就会发生：\n> Hash 冲突\n解决方式：\n```text\ntable[5]\n↓\nA\n↓\nB\n↓\nC\n```\n也就是链表\n---', 0, 'AUTO', 'CHILD', 'TEXT', NULL, '八、Hash 冲突', '八、Hash 冲突', NULL, NULL, NULL, NULL, NULL, NULL, '2026-09-20 22:37:16', '2026-09-20 22:37:16');
INSERT INTO `chunk` VALUES (1134, 3, 10, '705879', 17, '如果 Hash 冲突严重：\n```text\nA\n↓\nB\n↓\nC\n↓\nD\n↓\nE\n↓\n...\n```\n链表查询最坏复杂度：\n```text\nO(n)\n```\n转换成红黑树以后：\n```text\n        D\n      /   \\\n     B     F\n    / \\   / \\\n   A   C E   G\n```\n查询复杂度约：\n```text\nO(log n)\n```\n所以：\n> 红黑树用于优化严重 Hash 冲突场景\n---', 0, 'AUTO', 'CHILD', 'TEXT', NULL, '九、为什么使用红黑树', '九、为什么使用红黑树', NULL, NULL, NULL, NULL, NULL, NULL, '2026-09-20 22:37:16', '2026-09-20 22:37:16');
INSERT INTO `chunk` VALUES (1135, 3, 10, '705879', 18, '```text\n8\n6\n64\n```\n分别表示：\n```java\nTREEIFY_THRESHOLD = 8;\nUNTREEIFY_THRESHOLD = 6;\nMIN_TREEIFY_CAPACITY = 64;\n```\n口诀：\n> 8 树化，6 退化，64 才树化\n---', 0, 'AUTO', 'CHILD', 'TEXT', NULL, '十、HashMap 三个必背数字', '十、HashMap 三个必背数字', NULL, NULL, NULL, NULL, NULL, NULL, '2026-09-20 22:37:16', '2026-09-20 22:37:16');
INSERT INTO `chunk` VALUES (1136, 3, 10, '705879', 19, '需要满足：\n```text\n桶中节点数量 >= 8\n```\n并且：\n```text\n数组容量 >= 64\n```\n才会树化\n否则：\n```text\n容量 < 64\n```\nHashMap 更倾向：\n> 先扩容，而不是立即树化\n---', 0, 'AUTO', 'CHILD', 'TEXT', NULL, '十一、链表什么时候转红黑树', '十一、链表什么时候转红黑树', NULL, NULL, NULL, NULL, NULL, NULL, '2026-09-20 22:37:16', '2026-09-20 22:37:16');
INSERT INTO `chunk` VALUES (1137, 3, 10, '705879', 20, '常记：\n```text\n节点数量 <= 6\n```\n红黑树可能退化为链表\n尤其是在：\n> HashMap resize 扩容以后，红黑树桶拆分时\n如果拆分后的某一边：\n```text\n节点数 <= 6\n```\n就会执行：\n```text\n红黑树\n↓\n链表\n```\n这个过程叫：\n> untreeify\n---', 0, 'AUTO', 'CHILD', 'TEXT', NULL, '十二、红黑树什么时候退化成链表', '十二、红黑树什么时候退化成链表', NULL, NULL, NULL, NULL, NULL, NULL, '2026-09-20 22:37:16', '2026-09-20 22:37:16');
INSERT INTO `chunk` VALUES (1138, 3, 10, '705879', 21, '如果：\n```text\n8 -> 树\n7 -> 链表\n```\n就容易：\n```text\n7\n↓\n插入\n↓\n8\n↓\n树化\n\n删除\n↓\n7\n↓\n链表\n\n再插入\n↓\n8\n↓\n再次树化\n```\n频繁转换成本很高\n所以设计成：\n```text\n6        7        8\n↓                 ↓\n链表            红黑树\n```\n7 相当于缓冲区\n---', 0, 'AUTO', 'CHILD', 'TEXT', NULL, '为什么是 8 和 6', '十二、红黑树什么时候退化成链表 > 为什么是 8 和 6', NULL, NULL, NULL, NULL, NULL, NULL, '2026-09-20 22:37:16', '2026-09-20 22:37:16');
INSERT INTO `chunk` VALUES (1139, 3, 10, '705879', 22, '```text\nput(key,value)\n↓\n计算 hash\n↓\n计算桶下标\n↓\n桶为空？\n├─ 是\n│   ↓\n│ 直接插入\n│\n└─ 否\n    ↓\n  key 是否存在？\n    ├─ 是\n    │   ↓\n    │ 覆盖 value\n    │\n    └─ 否\n        ↓\n      链表 / 红黑树插入\n```\n---', 0, 'AUTO', 'CHILD', 'TEXT', NULL, '十三、HashMap put 流程', '十三、HashMap put 流程', NULL, NULL, NULL, NULL, NULL, NULL, '2026-09-20 22:37:16', '2026-09-20 22:37:16');
INSERT INTO `chunk` VALUES (1140, 3, 10, '705879', 23, '```text\nget(key)\n↓\n计算 hash\n↓\n计算 index\n↓\n定位桶\n↓\n链表 / 红黑树查找\n↓\n比较 hash\n↓\n比较 equals\n↓\n返回 value\n```\n注意：\n> hashCode 相同，不代表对象一定相等\n最终还需要：\n```java\nequals()\n```\n判断\n---', 0, 'AUTO', 'CHILD', 'TEXT', NULL, '十四、HashMap get 流程', '十四、HashMap get 流程', NULL, NULL, NULL, NULL, NULL, NULL, '2026-09-20 22:37:16', '2026-09-20 22:37:16');
INSERT INTO `chunk` VALUES (1141, 3, 10, '705879', 24, '正常情况下：\n```text\nput -> O(1)\n\nget -> O(1)\n```\nHash 冲突严重：\n链表：\n```text\nO(n)\n```\n红黑树：\n```text\nO(log n)\n```\n---', 0, 'AUTO', 'CHILD', 'TEXT', NULL, '十五、HashMap 时间复杂度', '十五、HashMap 时间复杂度', NULL, NULL, NULL, NULL, NULL, NULL, '2026-09-20 22:37:16', '2026-09-20 22:37:16');
INSERT INTO `chunk` VALUES (1142, 3, 10, '705879', 25, '默认负载因子：\n```java\n0.75f\n```\n默认容量常见：\n```text\n16\n```\n扩容阈值：\n```text\n16 × 0.75 = 12\n```\n超过阈值以后：\n```text\n16\n↓\n32\n↓\n64\n↓\n128\n```\n一般扩容为原来的：\n> 2 倍\n目的：\n> 减少 Hash 冲突\n---', 0, 'AUTO', 'CHILD', 'TEXT', NULL, '十六、HashMap 扩容', '十六、HashMap 扩容', NULL, NULL, NULL, NULL, NULL, NULL, '2026-09-20 22:37:16', '2026-09-20 22:37:16');
INSERT INTO `chunk` VALUES (1143, 3, 10, '705879', 26, '因为桶下标使用：\n```java\n(n - 1) & hash;\n```\n当 `n` 是 2 的幂时：\n```text\nn = 16\n\nn - 1 = 15\n```\n二进制：\n```text\n0000 1111\n```\n使用：\n```text\nhash & 0000 1111\n```\n就能快速得到：\n```text\n0 ~ 15\n```\n之间的下标\n优点：\n```text\n位运算快\n+\nHash 分布较均匀\n```\n---', 0, 'AUTO', 'CHILD', 'TEXT', NULL, '十七、为什么 HashMap 容量是 2 的幂', '十七、为什么 HashMap 容量是 2 的幂', NULL, NULL, NULL, NULL, NULL, NULL, '2026-09-20 22:37:16', '2026-09-20 22:37:16');
INSERT INTO `chunk` VALUES (1144, 3, 10, '705879', 27, '> 索引是数据库为了快速定位数据而建立的一种有序数据结构，本质上是用空间换查询效率', 0, 'AUTO', 'CHILD', 'TEXT', NULL, '索引', '十八、面试一句话速答 > 索引', NULL, NULL, NULL, NULL, NULL, NULL, '2026-09-20 22:37:16', '2026-09-20 22:37:16');
INSERT INTO `chunk` VALUES (1145, 3, 10, '705879', 28, '> B+Tree 是一种多叉、平衡、有序树，树高度低、磁盘 IO 少，叶子节点有序连接，因此非常适合数据库索引、范围查询和排序', 0, 'AUTO', 'CHILD', 'TEXT', NULL, 'B+Tree', '十八、面试一句话速答 > B+Tree', NULL, NULL, NULL, NULL, NULL, NULL, '2026-09-20 22:37:16', '2026-09-20 22:37:16');
INSERT INTO `chunk` VALUES (1146, 3, 10, '705879', 29, '> 二级索引查到主键以后，再通过主键去聚簇索引查询完整数据，这个过程叫回表', 0, 'AUTO', 'CHILD', 'TEXT', NULL, '回表', '十八、面试一句话速答 > 回表', NULL, NULL, NULL, NULL, NULL, NULL, '2026-09-20 22:37:16', '2026-09-20 22:37:16');
INSERT INTO `chunk` VALUES (1147, 3, 10, '705879', 30, '> 查询需要的字段全部可以从索引中获取，不需要回表，就叫覆盖索引', 0, 'AUTO', 'CHILD', 'TEXT', NULL, '覆盖索引', '十八、面试一句话速答 > 覆盖索引', NULL, NULL, NULL, NULL, NULL, NULL, '2026-09-20 22:37:16', '2026-09-20 22:37:16');
INSERT INTO `chunk` VALUES (1148, 3, 10, '705879', 31, '> JDK 8 的 HashMap 底层采用数组 + 链表 + 红黑树结构，通过 hash 定位桶，发生冲突时使用链表，冲突严重时转成红黑树', 0, 'AUTO', 'CHILD', 'TEXT', NULL, 'HashMap', '十八、面试一句话速答 > HashMap', NULL, NULL, NULL, NULL, NULL, NULL, '2026-09-20 22:37:16', '2026-09-20 22:37:16');
INSERT INTO `chunk` VALUES (1149, 3, 10, '705879', 32, '> 桶内节点达到 8 且数组容量达到 64 时，可以从链表转成红黑树', 0, 'AUTO', 'CHILD', 'TEXT', NULL, 'HashMap 树化', '十八、面试一句话速答 > HashMap 树化', NULL, NULL, NULL, NULL, NULL, NULL, '2026-09-20 22:37:16', '2026-09-20 22:37:16');
INSERT INTO `chunk` VALUES (1150, 3, 10, '705879', 33, '> 红黑树节点较少时可以退化成链表，典型阈值是 6，尤其是在 resize 拆分红黑树桶时\n---', 0, 'AUTO', 'CHILD', 'TEXT', NULL, 'HashMap 反树化', '十八、面试一句话速答 > HashMap 反树化', NULL, NULL, NULL, NULL, NULL, NULL, '2026-09-20 22:37:16', '2026-09-20 22:37:16');
INSERT INTO `chunk` VALUES (1151, 3, 10, '705879', 34, '```text\n索引 = 数据库目录\n\n普通索引 = 提速\n\n唯一索引 = 提速 + 防重复\n\n联合索引 = 多字段索引\n\n联合索引 = 最左匹配\n\nB+Tree = 多叉 + 平衡 + 有序 + 叶子链表\n\n回表 = 二级索引 → 主键 → 完整数据\n\n覆盖索引 = 不回表\n\nHashMap = 数组 + 链表 + 红黑树\n\nHashMap = hash → 桶 → 查找\n\n8 树化\n6 退化\n64 才树化\n\n负载因子 = 0.75\n\n扩容 = 通常 2 倍\n\nget / put 平均 = O(1)\n\n链表最坏 = O(n)\n\n红黑树 = O(log n)\n```', 0, 'AUTO', 'CHILD', 'TEXT', NULL, '十九、最终口诀', '十九、最终口诀', NULL, NULL, NULL, NULL, NULL, NULL, '2026-09-20 22:37:16', '2026-09-20 22:37:16');

-- ----------------------------
-- Table structure for document
-- ----------------------------
CREATE TABLE `document`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `kb_id` bigint NOT NULL,
  `tenant_id` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `file_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `object_key` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'MinIO 对象键 {tenantId}/{kbId}/{date}/{uuid}.{ext}',
  `file_size` bigint NOT NULL DEFAULT 0,
  `mime_type` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'PARSING' COMMENT 'PARSING/CHUNKING/EMBEDDING/INDEXING/READY/FAILED',
  `progress` tinyint NOT NULL DEFAULT 0 COMMENT '处理进度百分比 0~100',
  `page_count` int NOT NULL DEFAULT 0,
  `warning` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '流水线降级告警(如OCR不可用)',
  `error_msg` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_kb`(`kb_id` ASC) USING BTREE,
  INDEX `idx_tenant`(`tenant_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 11 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '文档' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of document
-- ----------------------------
INSERT INTO `document` VALUES (10, 3, '705879', '17092026_MySQL 索引与 Java HashMap 速记笔记.md', '705879/3/2026-09-20/669ebccf-11f6-41fa-9ea0-d7a1e547f527.md', 9885, 'text/markdown', 'READY', 100, 1, NULL, NULL, '2026-09-20 22:37:14', '2026-09-20 22:37:20');

-- ----------------------------
-- Table structure for generated_image
-- ----------------------------
CREATE TABLE `generated_image`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `user_id` bigint NOT NULL,
  `prompt` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `model` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `size` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `seed` bigint NULL DEFAULT NULL,
  `object_key` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `file_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `file_size` bigint NULL DEFAULT NULL,
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'SUCCESS',
  `error_msg` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_tenant_user`(`tenant_id` ASC, `user_id` ASC, `id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 4 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '文生图生成历史' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of generated_image
-- ----------------------------

-- ----------------------------
-- Table structure for knowledge_base
-- ----------------------------
CREATE TABLE `knowledge_base`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `description` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `milvus_collection` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '绑定的 Milvus 集合 kb_{id}',
  `es_index` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '绑定的 ES 索引 kb_{id}',
  `chunk_size` int NOT NULL DEFAULT 500 COMMENT '切片大小(字符)',
  `chunk_overlap` int NOT NULL DEFAULT 80 COMMENT '切片重叠(字符)',
  `parent_chunk_size` int NOT NULL DEFAULT 2000 COMMENT 'Parent 大小（tokens）',
  `child_chunk_size` int NOT NULL DEFAULT 500 COMMENT 'Child 大小（tokens）',
  `child_overlap` int NOT NULL DEFAULT 80 COMMENT 'Child 重叠（tokens）',
  `chunk_strategy` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'AUTO' COMMENT 'FIXED_SIZE/RECURSIVE/PARAGRAPH/SENTENCE/SEMANTIC/STRUCTURE/MARKDOWN/HTML/PDF_LAYOUT/TABLE/QA/PARENT_CHILD/SLIDING_WINDOW/CODE/AUTO',
  `separators` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '[\"\\n\\n\",\"\\n\",\"。\",\"？\",\"！\"]' COMMENT '分隔符 JSON 数组',
  `semantic_refine_enabled` tinyint NOT NULL DEFAULT 0 COMMENT '是否在结构化切片后启用语义边界细化（§14）',
  `semantic_similarity_threshold` double NOT NULL DEFAULT 0.72 COMMENT '相邻句向量余弦阈值，< 则判为主题边界',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_tenant`(`tenant_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 5 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '知识库' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of knowledge_base
-- ----------------------------
INSERT INTO `knowledge_base` VALUES (3, '705879', 'Java', NULL, 'kb_3', 'kb_3', 500, 80, 1200, 400, 60, 'AUTO', '[\"。\",\"？\",\"！\"]', 0, 0.72, '2026-09-19 23:37:52', '2026-09-19 23:37:52');
INSERT INTO `knowledge_base` VALUES (4, '866818', 'PERM-KB1', NULL, 'kb_4', 'kb_4', 500, 80, 1200, 400, 60, 'AUTO', '[\"\\n\\n\",\"\\n\",\"。\",\"？\",\"！\"]', 0, 0.72, '2026-09-20 03:14:26', '2026-09-20 03:14:26');

-- ----------------------------
-- Table structure for model
-- ----------------------------
CREATE TABLE `model`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `base_url` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `api_key` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `model` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `temperature` decimal(3, 2) NULL DEFAULT NULL,
  `top_p` decimal(3, 2) NULL DEFAULT NULL,
  `max_tokens` int NULL DEFAULT NULL,
  `embedding_dim` int NULL DEFAULT NULL,
  `enabled` tinyint NOT NULL DEFAULT 0,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_tenant_type_enabled`(`tenant_id` ASC, `type` ASC, `enabled` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 13 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of model
-- ----------------------------

-- ----------------------------
-- Table structure for pipeline_task
-- ----------------------------
CREATE TABLE `pipeline_task`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `kb_id` bigint NOT NULL,
  `document_id` bigint NOT NULL,
  `type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'PARSE/REINDEX',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCESS/FAILED',
  `retry_count` int NOT NULL DEFAULT 0,
  `payload` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT 'MQ 消息 JSON',
  `error_msg` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_doc`(`document_id` ASC) USING BTREE,
  INDEX `idx_tenant`(`tenant_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 15 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '流水线任务账本' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of pipeline_task
-- ----------------------------
INSERT INTO `pipeline_task` VALUES (1, '705879', 1, 1, 'PARSE', 'FAILED', 4, '{\"taskId\":1,\"type\":\"PARSE\",\"tenantId\":\"705879\",\"kbId\":1,\"documentId\":1,\"objectKey\":\"705879/1/2026-09-19/f6931526-0e73-479f-b040-214be7e1cf63.md\"}', '403 Forbidden from POST https://llm-ifirtj3suxqtdb8t.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/embeddings', '2026-09-19 20:25:08', '2026-09-19 20:26:19');
INSERT INTO `pipeline_task` VALUES (2, '705879', 1, 2, 'PARSE', 'FAILED', 4, '{\"taskId\":2,\"type\":\"PARSE\",\"tenantId\":\"705879\",\"kbId\":1,\"documentId\":2,\"objectKey\":\"705879/1/2026-09-19/913c8be9-6f19-4f5a-8294-9ecc556ca535.md\"}', '401 Unauthorized from POST https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings', '2026-09-19 20:29:54', '2026-09-19 20:31:02');
INSERT INTO `pipeline_task` VALUES (3, '705879', 1, 3, 'PARSE', 'RUNNING', 0, '{\"taskId\":3,\"type\":\"PARSE\",\"tenantId\":\"705879\",\"kbId\":1,\"documentId\":3,\"objectKey\":\"705879/1/2026-09-19/5cb8dab2-9c79-4ad0-a9d3-3449728efc9f.md\"}', NULL, '2026-09-19 20:43:11', '2026-09-19 20:43:12');
INSERT INTO `pipeline_task` VALUES (4, '705879', 2, 4, 'PARSE', 'RUNNING', 0, '{\"taskId\":4,\"type\":\"PARSE\",\"tenantId\":\"705879\",\"kbId\":2,\"documentId\":4,\"objectKey\":\"705879/2/2026-09-19/81420a6a-db9f-469c-9c9e-d3daa4071b16.md\"}', NULL, '2026-09-19 20:50:39', '2026-09-19 20:50:39');
INSERT INTO `pipeline_task` VALUES (5, '705879', 2, 5, 'PARSE', 'RUNNING', 0, '{\"taskId\":5,\"type\":\"PARSE\",\"tenantId\":\"705879\",\"kbId\":2,\"documentId\":5,\"objectKey\":\"705879/2/2026-09-19/91610a4e-4e79-4158-a21e-3d64df5920be.md\"}', NULL, '2026-09-19 21:30:16', '2026-09-19 21:30:17');
INSERT INTO `pipeline_task` VALUES (6, '705879', 2, 6, 'PARSE', 'RUNNING', 0, '{\"taskId\":6,\"type\":\"PARSE\",\"tenantId\":\"705879\",\"kbId\":2,\"documentId\":6,\"objectKey\":\"705879/2/2026-09-19/3d77ea94-dbea-489d-b791-d57c81e97312.md\"}', NULL, '2026-09-19 23:07:19', '2026-09-19 23:07:20');
INSERT INTO `pipeline_task` VALUES (7, '705879', 2, 4, 'REINDEX', 'RUNNING', 0, NULL, NULL, '2026-09-19 23:18:46', '2026-09-19 23:18:47');
INSERT INTO `pipeline_task` VALUES (8, '705879', 2, 6, 'REINDEX', 'RUNNING', 0, NULL, NULL, '2026-09-19 23:30:15', '2026-09-19 23:30:16');
INSERT INTO `pipeline_task` VALUES (9, '705879', 2, 5, 'REINDEX', 'RUNNING', 0, NULL, NULL, '2026-09-19 23:30:20', '2026-09-19 23:30:20');
INSERT INTO `pipeline_task` VALUES (10, '705879', 2, 4, 'REINDEX', 'RUNNING', 0, NULL, NULL, '2026-09-19 23:30:22', '2026-09-19 23:30:22');
INSERT INTO `pipeline_task` VALUES (14, '705879', 3, 10, 'PARSE', 'RUNNING', 0, '{\"taskId\":14,\"type\":\"PARSE\",\"tenantId\":\"705879\",\"kbId\":3,\"documentId\":10,\"objectKey\":\"705879/3/2026-09-20/669ebccf-11f6-41fa-9ea0-d7a1e547f527.md\"}', NULL, '2026-09-20 22:37:14', '2026-09-20 22:37:15');

-- ----------------------------
-- Table structure for prompt_template
-- ----------------------------
CREATE TABLE `prompt_template`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `kb_id` bigint NOT NULL,
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '模板名称',
  `content` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '系统提示词内容，支持占位符 {{资料}} {{外部信息}} {{问题}}',
  `is_default` tinyint NOT NULL DEFAULT 0 COMMENT '1默认模板 0普通',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_tenant`(`tenant_id` ASC) USING BTREE,
  INDEX `idx_kb`(`kb_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 4 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '提示词模板' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of prompt_template
-- ----------------------------
INSERT INTO `prompt_template` VALUES (2, '705879', 3, '默认知识库问答模板', '你是企业知识库智能问答助手。请优先依据【资料】回答用户问题；引用资料时在句末标注角标序号，如 [1]。【外部信息】仅作补充参考。若资料与问题无关且无外部信息，请如实说明未找到相关内容。\n\n【资料】\n{{资料}}\n{{外部信息}}', 1, '2026-09-20 02:31:00', '2026-09-20 02:31:00');
INSERT INTO `prompt_template` VALUES (3, '866818', 4, '默认知识库问答模板', '你是企业知识库智能问答助手。请优先依据【资料】回答用户问题；引用资料时在句末标注角标序号，如 [1]。【外部信息】仅作补充参考。若资料与问题无关且无外部信息，请如实说明未找到相关内容。\n\n【资料】\n{{资料}}\n{{外部信息}}', 1, '2026-09-20 03:14:26', '2026-09-20 03:14:26');

-- ----------------------------
-- Table structure for sys_menu
-- ----------------------------
CREATE TABLE `sys_menu`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '菜单码',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '显示名称',
  `path` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '前端路由路径',
  `icon` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '图标标识',
  `platform_visible` tinyint NOT NULL DEFAULT 0 COMMENT '平台管理员可见',
  `admin_visible` tinyint NOT NULL DEFAULT 0 COMMENT '租户管理员可见',
  `end_user` tinyint NOT NULL DEFAULT 0 COMMENT '可授权给普通用户',
  `sort` int NOT NULL DEFAULT 0,
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '产品级启用',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_menu_code`(`code` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 11 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '菜单目录' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of sys_menu
-- ----------------------------
INSERT INTO `sys_menu` VALUES (1, 'tenants', '租户管理', '/tenants', 'team', 1, 0, 0, 10, 1, '2026-09-20 03:36:01', '2026-09-20 03:36:01');
INSERT INTO `sys_menu` VALUES (2, 'chat', '智能问答', '/chat', 'message', 0, 1, 1, 10, 1, '2026-09-20 03:36:01', '2026-09-20 03:36:01');
INSERT INTO `sys_menu` VALUES (3, 'image-studio', 'AI 画图', '/image-studio', 'picture', 0, 1, 1, 20, 1, '2026-09-20 03:36:01', '2026-09-20 03:36:01');
INSERT INTO `sys_menu` VALUES (4, 'knowledge-bases', '知识库管理', '/knowledge-bases', 'book', 0, 1, 0, 30, 1, '2026-09-20 03:36:01', '2026-09-20 03:36:01');
INSERT INTO `sys_menu` VALUES (5, 'users', '用户管理', '/users', 'team', 0, 1, 0, 40, 1, '2026-09-20 03:36:01', '2026-09-20 03:36:01');
INSERT INTO `sys_menu` VALUES (6, 'model-config', '模型参数', '/model-config', 'setting', 0, 1, 0, 50, 1, '2026-09-20 03:36:01', '2026-09-20 03:36:01');
INSERT INTO `sys_menu` VALUES (7, 'prompts', '提示词管理', '/prompts', 'bulb', 0, 1, 0, 60, 1, '2026-09-20 03:36:01', '2026-09-20 03:36:01');
INSERT INTO `sys_menu` VALUES (8, 'tool-config', '功能配置', '/tool-config', 'tool', 0, 1, 0, 70, 1, '2026-09-20 03:36:01', '2026-09-20 03:36:01');
INSERT INTO `sys_menu` VALUES (9, 'quotas', '配额用量', '/quotas', 'dashboard', 0, 1, 0, 80, 1, '2026-09-20 03:36:01', '2026-09-20 03:36:01');
INSERT INTO `sys_menu` VALUES (10, 'subtitle', '字幕转换', '/subtitle', 'edit', 0, 1, 1, 25, 1, '2026-10-03 00:00:00', '2026-10-03 00:00:00');

-- ----------------------------
-- Table structure for sys_tool
-- ----------------------------
CREATE TABLE `sys_tool`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '工具码（个人授权用）',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '显示名称',
  `fn_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'LLM function 名',
  `description` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '功能说明（配置页展示）',
  `requires_key` tinyint NOT NULL DEFAULT 0 COMMENT '是否需配置 API Key',
  `sort` int NOT NULL DEFAULT 0,
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '产品级启用',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_tool_code`(`code` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 3 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '工具目录' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of sys_tool
-- ----------------------------
INSERT INTO `sys_tool` VALUES (1, 'weather', '天气查询', 'query_weather', '识别气象意图与地域后，调用免费气象接口，将实时天气注入问答上下文。', 0, 10, 1, '2026-09-20 03:36:01', '2026-09-20 03:36:01');
INSERT INTO `sys_tool` VALUES (2, 'tavily', '联网搜索', 'tavily_search', '用户询问最新资讯或需要联网信息时自动检索。', 1, 20, 1, '2026-09-20 03:36:01', '2026-09-20 03:36:01');

-- ----------------------------
-- Table structure for sys_user
-- ----------------------------
CREATE TABLE `sys_user`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '000000' COMMENT '租户ID，000000代表超级平台租户',
  `username` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '登录账号',
  `password_hash` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'BCrypt 密码',
  `user_type` tinyint NOT NULL DEFAULT 1 COMMENT '0=平台超级管理员, 1=租户管理员, 2=租户普通用户',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '1正常 0停用',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_tenant_username`(`tenant_id` ASC, `username` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 6 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '统一用户表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of sys_user
-- ----------------------------
INSERT INTO `sys_user` VALUES (1, '000000', 'admin', '$2a$10$CgnVRPmtAuYoCmYQ4ly6de1CHQAtbOraAP8Z7yg6BVVzGvOaLxfom', 0, 1, '2026-09-19 20:13:35', '2026-09-19 20:13:35');
INSERT INTO `sys_user` VALUES (2, '705879', 'bytedance4j@gmail.com', '$2a$10$WMCdsx6B6SNO.wKL5v8mbOw62E0oj48g4IthwKvmfv3DfkD6zgYU6', 1, 1, '2026-09-19 20:15:34', '2026-09-20 01:21:49');
INSERT INTO `sys_user` VALUES (3, '705879', 'user01', '$2a$10$uReib.ybEe5.m8QfhhTVjOEZRywSMZwh4v2GYW46aUY5RfNr93kDi', 2, 1, '2026-09-20 02:07:09', '2026-09-20 03:45:10');
INSERT INTO `sys_user` VALUES (4, '866818', 'admin_866818', '$2a$10$fHpp.lIKNEbRY7W9MqnYEukZczLT.2ekcdH4HjtjRehHiSHlQaJda', 1, 1, '2026-09-20 03:10:54', '2026-09-20 04:19:40');
INSERT INTO `sys_user` VALUES (5, '866818', 'tester01', '$2a$10$49AeimLkE8S.MVh74Ml5OuaJ8hsXm6Wmnp/zZ6IUIXLpQ7wtsKQxy', 2, 1, '2026-09-20 03:10:54', '2026-09-20 04:19:41');

-- ----------------------------
-- Table structure for tenant
-- ----------------------------
CREATE TABLE `tenant`  (
  `id` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '租户ID，000000代表超级平台租户',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '租户名称',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  `max_storage_mb` int NOT NULL DEFAULT 1024 COMMENT 'MinIO 存储上限(MB)',
  `max_mq_concurrency` int NOT NULL DEFAULT 4 COMMENT 'MQ 任务并发额度',
  `max_llm_tokens_month` bigint NOT NULL DEFAULT 1000000 COMMENT 'LLM Token 月度阈值',
  `max_sse_connections` int NOT NULL DEFAULT 20 COMMENT '最大并发 SSE 连接数',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `code` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '租户编码（字母/数字/下划线，10-20位）',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_tenant_code`(`code` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '租户表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of tenant
-- ----------------------------
INSERT INTO `tenant` VALUES ('000000', '超级平台租户', 1, 102400, 16, 10000000, 100, '2026-09-19 20:13:34', '2026-09-20 01:30:49', 'SUPER_TENANT');
INSERT INTO `tenant` VALUES ('705879', 'acme', 1, 102400, 4, 1000000, 20, '2026-09-19 20:15:34', '2026-09-20 01:30:49', 'TENANT705879');
INSERT INTO `tenant` VALUES ('866818', 'PERM-TEST', 0, 1024, 4, 1000000, 20, '2026-09-20 03:10:54', '2026-09-20 04:19:41', 'PERMTEST01');

-- ----------------------------
-- Table structure for tenant_menu
-- ----------------------------
CREATE TABLE `tenant_menu`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `menu_code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `enabled` tinyint NOT NULL DEFAULT 1,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_tenant_menu`(`tenant_id` ASC, `menu_code` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 7 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '功能菜单租户总开关' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of tenant_menu
-- ----------------------------
INSERT INTO `tenant_menu` VALUES (1, '866818', 'image-studio', 1, '2026-09-20 03:36:01', '2026-09-20 03:36:01');
INSERT INTO `tenant_menu` VALUES (2, '866818', 'chat', 1, '2026-09-20 03:36:01', '2026-09-20 03:36:01');
INSERT INTO `tenant_menu` VALUES (3, '000000', 'image-studio', 1, '2026-09-20 03:36:01', '2026-09-20 03:36:01');
INSERT INTO `tenant_menu` VALUES (4, '000000', 'chat', 1, '2026-09-20 03:36:01', '2026-09-20 03:36:01');
INSERT INTO `tenant_menu` VALUES (5, '705879', 'image-studio', 1, '2026-09-20 03:36:01', '2026-09-20 03:36:01');
INSERT INTO `tenant_menu` VALUES (6, '705879', 'chat', 1, '2026-09-20 03:36:01', '2026-09-20 03:36:01');

-- ----------------------------
-- Table structure for tool_config
-- ----------------------------
CREATE TABLE `tool_config`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `weather_enabled` tinyint NOT NULL DEFAULT 0,
  `tavily_enabled` tinyint NOT NULL DEFAULT 0,
  `tavily_api_key` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_tenant`(`tenant_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 3 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '租户 Agent 工具配置' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of tool_config
-- ----------------------------

-- ----------------------------
-- Table structure for user_feature
-- ----------------------------
CREATE TABLE `user_feature`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `user_id` bigint NOT NULL,
  `feature_type` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'MENU / TOOL',
  `code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_user_feature`(`user_id` ASC, `feature_type` ASC, `code` ASC) USING BTREE,
  INDEX `idx_user_id`(`user_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 31 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户功能授权（菜单/工具）' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of user_feature
-- ----------------------------
INSERT INTO `user_feature` VALUES (24, '866818', 5, 'MENU', 'chat', '2026-09-20 04:19:41');
INSERT INTO `user_feature` VALUES (25, '866818', 5, 'MENU', 'image-studio', '2026-09-20 04:19:41');
INSERT INTO `user_feature` VALUES (26, '866818', 5, 'TOOL', 'weather', '2026-09-20 04:19:41');
INSERT INTO `user_feature` VALUES (27, '705879', 3, 'MENU', 'chat', '2026-09-20 04:25:51');
INSERT INTO `user_feature` VALUES (28, '705879', 3, 'MENU', 'image-studio', '2026-09-20 04:25:51');
INSERT INTO `user_feature` VALUES (29, '705879', 3, 'TOOL', 'weather', '2026-09-20 04:25:56');
INSERT INTO `user_feature` VALUES (30, '705879', 3, 'TOOL', 'tavily', '2026-09-20 04:25:56');

-- ----------------------------
-- Table structure for user_kb
-- ----------------------------
CREATE TABLE `user_kb`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `user_id` bigint NOT NULL,
  `kb_id` bigint NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_user_kb`(`user_id` ASC, `kb_id` ASC) USING BTREE,
  INDEX `idx_tenant_user`(`tenant_id` ASC, `user_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 13 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户知识库授权' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of user_kb
-- ----------------------------
INSERT INTO `user_kb` VALUES (2, '705879', 3, 3, '2026-09-20 02:58:53');
INSERT INTO `user_kb` VALUES (12, '866818', 5, 4, '2026-09-20 03:41:36');

-- ----------------------------
-- Table structure for user_prompt
-- ----------------------------
CREATE TABLE `user_prompt`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `user_id` bigint NOT NULL,
  `prompt_id` bigint NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_user_prompt`(`user_id` ASC, `prompt_id` ASC) USING BTREE,
  INDEX `idx_tenant_user`(`tenant_id` ASC, `user_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 11 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户提示词模板授权' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of user_prompt
-- ----------------------------
INSERT INTO `user_prompt` VALUES (2, '705879', 3, 2, '2026-09-20 02:58:53');
INSERT INTO `user_prompt` VALUES (10, '866818', 5, 3, '2026-09-20 03:41:36');

-- ----------------------------
-- Table structure for subtitle
-- ----------------------------
CREATE TABLE `subtitle`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `user_id` bigint NOT NULL,
  `original_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '原始 VTT 文件名',
  `source_lang` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '源语言',
  `target_lang` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '目标语言，null 表示未翻译',
  `srt_content` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'SRT 格式字幕全文',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_tenant_user`(`tenant_id` ASC, `user_id` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '字幕转换记录' ROW_FORMAT = Dynamic;

SET FOREIGN_KEY_CHECKS = 1;
