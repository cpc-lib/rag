-- generated_image 增加反向提示词列（万相 wan2.7 negative_prompt，≤500 字符）
ALTER TABLE generated_image ADD COLUMN negative_prompt VARCHAR(500) NULL COMMENT '反向提示词' AFTER prompt;
