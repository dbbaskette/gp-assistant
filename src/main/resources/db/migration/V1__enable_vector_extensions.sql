-- Enable required extensions for Spring AI PgVectorStore
-- These extensions are needed for vector operations and UUID generation
-- Spring AI will handle the actual table creation with the correct dimensions

-- Enable vector extension (required for vector operations)
CREATE EXTENSION IF NOT EXISTS vector;

-- Enable UUID generation (required for primary keys)
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Enable hstore (used by Spring AI for metadata storage)
CREATE EXTENSION IF NOT EXISTS hstore;

-- Note: The gp_docs table will be automatically created by Spring AI's PgVectorStore
-- with the correct dimensions based on your embedding model configuration.
-- The dimensions are controlled by the APP_VECTORSTORE_DIMENSIONS environment variable
-- or the app.vectorstore.dimensions property in application.yaml