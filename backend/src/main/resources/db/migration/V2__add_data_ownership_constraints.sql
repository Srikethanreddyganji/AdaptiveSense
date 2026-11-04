CREATE INDEX IF NOT EXISTS idx_conversations_user_created_at
    ON conversations (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_emotion_analysis_conversation
    ON emotion_analysis (conversation_id, id DESC);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_conversations_user') THEN
        ALTER TABLE conversations
            ADD CONSTRAINT fk_conversations_user
            FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_emotion_analysis_conversation') THEN
        ALTER TABLE emotion_analysis
            ADD CONSTRAINT fk_emotion_analysis_conversation
            FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_user_memory_user') THEN
        ALTER TABLE user_memory
            ADD CONSTRAINT fk_user_memory_user
            FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
    END IF;
END $$;
