# MCP Server JSON Response Format Guide

## Overview

The `gp-assistant` frontend now automatically detects and renders JSON arrays as beautiful data tables. This document explains how to modify the `gp-mcp-server` to return JSON data that will be automatically converted to tables in the UI.

## Current Behavior

The gp-assistant frontend detects JSON arrays in two formats:

1. **JSON code blocks**: ` ```json [...] ``` `
2. **Plain JSON arrays**: `[{...}, {...}]`

When detected, these are automatically converted into styled HTML tables with:
- Sortable columns (future enhancement)
- Hover effects
- Responsive scrolling
- Row count display
- Nested object flattening

## Required Changes to gp-mcp-server

### 1. Tool Response Format

Modify your MCP tool responses to return JSON arrays instead of plain text descriptions.

**Before (Text Format):**
```typescript
return {
  content: [
    {
      type: "text",
      text: "Found 3 tables:\n- users (10 rows)\n- products (25 rows)\n- orders (15 rows)"
    }
  ]
};
```

**After (JSON Format):**
```typescript
return {
  content: [
    {
      type: "text",
      text: "Here are the tables in the database:\n```json\n" +
            JSON.stringify([
              { schema: "public", table: "users", rows: 10, size: "1.2 MB" },
              { schema: "public", table: "products", rows: 25, size: "3.5 MB" },
              { schema: "public", table: "orders", rows: 15, size: "2.1 MB" }
            ], null, 2) +
            "\n```"
    }
  ]
};
```

### 2. Schema Listing (gplistSchemas)

**Current Implementation Location:**
`gp-mcp-server/src/tools/listSchemas.ts` (or similar)

**Recommended JSON Format:**
```json
[
  {
    "schema": "public",
    "table": "users",
    "row_count": 1000,
    "size": "1.2 MB",
    "type": "TABLE"
  },
  {
    "schema": "public",
    "table": "orders",
    "row_count": 5000,
    "size": "8.5 MB",
    "type": "TABLE"
  }
]
```

**Example Implementation:**
```typescript
async function listSchemas(args: any): Promise<any> {
  const client = await pool.connect();
  try {
    const result = await client.query(`
      SELECT
        schemaname as schema,
        tablename as table,
        n_live_tup as row_count,
        pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename)) as size,
        'TABLE' as type
      FROM pg_stat_user_tables
      ORDER BY schemaname, tablename
    `);

    const tables = result.rows;

    return {
      content: [
        {
          type: "text",
          text: `Found ${tables.length} tables in the database:\n\`\`\`json\n${JSON.stringify(tables, null, 2)}\n\`\`\``
        }
      ]
    };
  } finally {
    client.release();
  }
}
```

### 3. Query Results (gprunQuery)

**Recommended JSON Format:**
Return actual query results as JSON array

```json
[
  {
    "id": 1,
    "name": "John Doe",
    "email": "john@example.com",
    "created_at": "2025-01-15"
  },
  {
    "id": 2,
    "name": "Jane Smith",
    "email": "jane@example.com",
    "created_at": "2025-01-16"
  }
]
```

**Example Implementation:**
```typescript
async function runQuery(args: { query: string }): Promise<any> {
  const client = await pool.connect();
  try {
    const result = await client.query(args.query);

    if (result.rows.length === 0) {
      return {
        content: [{
          type: "text",
          text: "Query executed successfully. No rows returned."
        }]
      };
    }

    return {
      content: [
        {
          type: "text",
          text: `Query returned ${result.rows.length} rows:\n\`\`\`json\n${JSON.stringify(result.rows, null, 2)}\n\`\`\``
        }
      ]
    };
  } finally {
    client.release();
  }
}
```

### 4. Table Schema (gpgetTableSchema)

**Recommended JSON Format:**
```json
[
  {
    "column": "id",
    "type": "integer",
    "nullable": "NO",
    "default": "nextval('users_id_seq')",
    "distribution_key": true
  },
  {
    "column": "name",
    "type": "character varying(255)",
    "nullable": "YES",
    "default": null,
    "distribution_key": false
  }
]
```

### 5. Sample Data (gpgetSampleData)

**Recommended JSON Format:**
Just return the actual sample rows directly:

```json
[
  {
    "id": 1,
    "name": "Sample User 1",
    "email": "user1@example.com",
    "status": "active"
  },
  {
    "id": 2,
    "name": "Sample User 2",
    "email": "user2@example.com",
    "status": "active"
  }
]
```

## Frontend Table Detection Logic

The frontend JavaScript automatically:

1. **Detects JSON** in responses using regex patterns:
   - ` ```json [...] ``` ` (code blocks)
   - `[{...}]` (plain JSON arrays)

2. **Parses JSON** and validates it's an array of objects

3. **Flattens nested objects** for table display:
   - `{ user: { name: "John" } }` becomes column header "User - Name"

4. **Renders table** with:
   - Row count badge
   - Styled headers
   - Hover effects
   - Responsive scrolling

## Testing Your Changes

1. **Test with gp-assistant**:
   ```bash
   # Start gp-mcp-server
   cd gp-mcp-server
   npm start

   # Start gp-assistant
   cd gp-assistant
   ./run.sh
   ```

2. **Ask questions that trigger tool calls**:
   - "What tables are in my database?"
   - "Show me sample data from the users table"
   - "What columns are in the products table?"

3. **Verify table rendering**:
   - JSON should be displayed as a styled table
   - Not as raw JSON text
   - Should have hover effects and proper formatting

## Best Practices

### DO:
✅ Return JSON arrays with consistent structure
✅ Use descriptive column names (snake_case or camelCase)
✅ Include summary text before the JSON
✅ Wrap JSON in ` ```json ``` ` blocks for clarity
✅ Limit row counts to reasonable numbers (use pagination)
✅ Handle empty results gracefully

### DON'T:
❌ Return JSON as escaped strings
❌ Mix formats in a single response
❌ Return huge result sets without pagination
❌ Use inconsistent object structures in the same array
❌ Forget error handling

## Example: Complete Tool Implementation

```typescript
// Example: gplistTables tool with JSON response
export const gplistTables = {
  name: "gplistTables",
  description: "List all tables in a specific schema with metadata",

  inputSchema: {
    type: "object",
    properties: {
      schema: {
        type: "string",
        description: "Schema name (default: public)"
      }
    }
  },

  async execute(args: { schema?: string }) {
    const schema = args.schema || 'public';
    const client = await pool.connect();

    try {
      const result = await client.query(`
        SELECT
          schemaname as schema,
          tablename as table,
          n_live_tup as row_count,
          pg_size_pretty(pg_total_relation_size(quote_ident(schemaname) || '.' || quote_ident(tablename))) as size,
          CASE
            WHEN tablename LIKE '%_prt_%' THEN 'PARTITION'
            ELSE 'TABLE'
          END as type
        FROM pg_stat_user_tables
        WHERE schemaname = $1
        ORDER BY tablename
      `, [schema]);

      if (result.rows.length === 0) {
        return {
          content: [{
            type: "text",
            text: `No tables found in schema '${schema}'.`
          }]
        };
      }

      return {
        content: [{
          type: "text",
          text: `Found ${result.rows.length} tables in schema '${schema}':\n\`\`\`json\n${JSON.stringify(result.rows, null, 2)}\n\`\`\``
        }]
      };
    } catch (error) {
      return {
        content: [{
          type: "text",
          text: `Error listing tables: ${error.message}`
        }],
        isError: true
      };
    } finally {
      client.release();
    }
  }
};
```

## Migration Checklist

- [ ] Update `gplistSchemas` to return JSON array
- [ ] Update `gprunQuery` to return JSON results
- [ ] Update `gpgetTableSchema` to return JSON column info
- [ ] Update `gpgetSampleData` to return JSON rows
- [ ] Update `gplistTables` to return JSON table list
- [ ] Add error handling for JSON serialization
- [ ] Test all tools with gp-assistant frontend
- [ ] Verify table rendering in UI
- [ ] Check mobile responsiveness
- [ ] Update tool descriptions if needed

## Support

If you have questions or need help implementing these changes, please refer to:
- IMC-chatbot implementation: `/Users/dbbaskette/Projects/insurance-megacorp/imc-chatbot`
- GP-assistant frontend code: `/Users/dbbaskette/Projects/gp-assistant/src/main/resources/static/assets/js/chat.js`
- This guide: `/Users/dbbaskette/Projects/gp-assistant/MCP_SERVER_JSON_RESPONSE_GUIDE.md`
