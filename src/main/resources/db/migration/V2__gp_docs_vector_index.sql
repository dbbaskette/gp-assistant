-- ANN index for similarity search (cosine distance matches OpenAI embeddings well)
CREATE INDEX IF NOT EXISTS gp_docs_emb_ivfflat
  ON public.gp_docs
  USING ivfflat (embedding vector_cosine_ops)
  WITH (lists = 200);

-- Post-bulk load recommendations:
-- ANALYZE public.gp_docs;
-- REINDEX INDEX public.gp_docs_emb_ivfflat;