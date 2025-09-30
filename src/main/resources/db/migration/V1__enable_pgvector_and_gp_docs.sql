-- Enable vector extension once per database (idempotent)
-- Note: In Greenplum, the extension is called 'vector' not 'pgvector'
CREATE EXTENSION IF NOT EXISTS vector;

-- RAG chunks table for the Greenplum docs
-- NOTE: embedding dimension must match your embedding model
-- (OpenAI text-embedding-3-small = 1536 dims)
CREATE TABLE IF NOT EXISTS public.gp_docs (
  id           uuid PRIMARY KEY,
  doc_id       text        NOT NULL,               -- logical source id (e.g., "gpdb7-pdf")
  chunk_index  int         NOT NULL,               -- chunk sequence within a doc
  content      text        NOT NULL,               -- chunk text
  metadata     jsonb       DEFAULT '{}'::jsonb,    -- {page, section, source, ...}
  embedding    vector(1536)                        -- OpenAI text-embedding-3-small dimensions
)
DISTRIBUTED BY (id);

-- Note: In Greenplum, UNIQUE constraints must include distribution key (id)
-- We'll create a unique constraint that includes the id column
ALTER TABLE public.gp_docs
  ADD CONSTRAINT gp_docs_doc_and_index_unique
  UNIQUE (id, doc_id, chunk_index);

CREATE INDEX IF NOT EXISTS gp_docs_docid_idx
  ON public.gp_docs (doc_id, chunk_index);

COMMENT ON TABLE public.gp_docs IS
  'Vectorized chunks of Greenplum docs for RAG';
COMMENT ON COLUMN public.gp_docs.embedding IS
  'Embedding vector; dimension must match the chosen embedding model';