#!/bin/bash

# Greenplum Assistant Run Script
# Usage: ./run.sh [options]
# Options:
#   -b, --build     Build the project before running
#   -c, --clean     Clean build (mvn clean compile)
#   -h, --help      Show this help message

set -e  # Exit on error

# Colors for output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Default options
BUILD=false
CLEAN=false
ENV_FILE=".env"

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        -b|--build)
            BUILD=true
            shift
            ;;
        -c|--clean)
            BUILD=true
            CLEAN=true
            shift
            ;;
        -h|--help)
            echo "Greenplum Assistant Run Script"
            echo ""
            echo "Usage: ./run.sh [options]"
            echo ""
            echo "Options:"
            echo "  -b, --build     Build the project before running (mvn compile)"
            echo "  -c, --clean     Clean build before running (mvn clean compile)"
            echo "  -h, --help      Show this help message"
            echo ""
            echo "Examples:"
            echo "  ./run.sh                # Run without building"
            echo "  ./run.sh -b             # Build and run"
            echo "  ./run.sh -c             # Clean build and run"
            echo ""
            echo "Environment Variables (all optional):"
            echo "  OPENAI_API_KEY             Route traffic to OpenAI when provided"
            echo "  LOCAL_MODEL_BASE_URL       Override local gateway base URL"
            echo "  APP_VECTORSTORE_DIMENSIONS Match pgvector schema to embedding width"
            echo "  SPRING_AI_MCP_CLIENT_ENABLED  Enable MCP client (default: false)"
            echo "  DOCS_INGEST_ON_STARTUP     Ingest docs on startup (default: true)"
            exit 0
            ;;
        *)
            echo -e "${RED}Error: Unknown option $1${NC}"
            echo "Use -h or --help for usage information"
            exit 1
            ;;
    esac
done

# Auto-load .env if present (allows simple KEY=value entries)
if [ -f "$ENV_FILE" ]; then
    echo -e "${BLUE}==> Loading environment from ${ENV_FILE}${NC}"
    set -a
    # shellcheck disable=SC1090
    source "$ENV_FILE"
    set +a
fi

# Build if requested
if [ "$BUILD" = true ]; then
    if [ "$CLEAN" = true ]; then
        echo -e "${BLUE}==> Running clean build...${NC}"
        ./mvnw clean compile -DskipTests
    else
        echo -e "${BLUE}==> Building project...${NC}"
        ./mvnw compile -DskipTests
    fi
    
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}✓ Build successful${NC}"
    else
        echo -e "${RED}✗ Build failed${NC}"
        exit 1
    fi
    echo ""
fi

# Display configuration
echo -e "${BLUE}==> Starting Greenplum Assistant${NC}"
echo -e "Version: Spring Boot 3.5.6, Spring AI 1.1.0-SNAPSHOT"
echo -e "MCP Client: ${SPRING_AI_MCP_CLIENT_ENABLED:-false}"
echo -e "Ingest on Startup: ${DOCS_INGEST_ON_STARTUP:-true}"
echo ""

# Run the application
echo -e "${GREEN}==> Running application...${NC}"

if [ -z "$OPENAI_API_KEY" ]; then
    echo -e "${YELLOW}→ No OPENAI_API_KEY detected. Using local model defaults.${NC}"
    CHAT_MODEL_DISPLAY=${LOCAL_CHAT_MODEL:-local-chat-model}
    EMBED_MODEL_DISPLAY=${LOCAL_EMBEDDING_MODEL:-local-embedding-model}
    BASE_URL_DISPLAY=${OPENAI_BASE_URL:-${LOCAL_MODEL_BASE_URL:-http://127.0.0.1:1234}}
    EMBED_BASE_URL_DISPLAY=${OPENAI_EMBEDDING_BASE_URL:-$BASE_URL_DISPLAY}
else
    echo -e "${GREEN}→ OPENAI_API_KEY detected. Using OpenAI endpoints.${NC}"
    CHAT_MODEL_DISPLAY=${OPENAI_CHAT_MODEL:-gpt-4o-mini}
    EMBED_MODEL_DISPLAY=${OPENAI_EMBEDDING_MODEL:-text-embedding-3-small}
    BASE_URL_DISPLAY=${OPENAI_BASE_URL:-https://api.openai.com}
    EMBED_BASE_URL_DISPLAY=${OPENAI_EMBEDDING_BASE_URL:-$BASE_URL_DISPLAY}
fi

EMBED_PATH_DISPLAY=${OPENAI_EMBEDDINGS_PATH:-/v1/embeddings}

echo -e "${BLUE}Chat model: ${CHAT_MODEL_DISPLAY}${NC}"
echo -e "${BLUE}Embedding model: ${EMBED_MODEL_DISPLAY}${NC}"
echo -e "${BLUE}Base URL: ${BASE_URL_DISPLAY}${NC}"
echo -e "${BLUE}Embedding endpoint: ${EMBED_BASE_URL_DISPLAY}${EMBED_PATH_DISPLAY}${NC}"

./mvnw spring-boot:run
