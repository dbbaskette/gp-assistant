const chatWindow = document.getElementById('chat-window');
const messageTemplate = document.getElementById('message-template');
const chatForm = document.getElementById('chat-form');
const chatInput = document.getElementById('chat-input');
const targetVersionSelect = document.getElementById('target-version');
const databaseSelect = document.getElementById('database-select');
const schemaSelect = document.getElementById('schema-select');
const modelModeEl = document.getElementById('model-mode');
const endpointMetaEl = document.getElementById('endpoint-meta');

const conversationKey = 'gp-assistant-conversation-id';
let conversationId = window.localStorage.getItem(conversationKey);

let configCache;

const versionBaselines = {
  '7.0': ['6.x', '7.x'],
  '6.0': ['5.x', '6.x'],
  '5.0': ['5.x'],
  'postgresql': ['13', '14', '15']
};

const htmlEscapeMap = Object.freeze({
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;'
});

function escapeHtml(str) {
  return str.replace(/[&<>"']/g, (c) => htmlEscapeMap[c] || c);
}

function renderMarkdown(text) {
  if (!text) return '';

  // First, detect and convert JSON tables
  text = detectAndRenderTables(text);

  const codeBlocks = [];
  let processed = text.replace(/```([\s\S]*?)```/g, (_, code) => {
    const token = `%%CODE_BLOCK_${codeBlocks.length}%%`;
    codeBlocks.push(`<pre><code>${escapeHtml(code.trim())}</code></pre>`);
    return token;
  });
  processed = processed.replace(/`([^`]+)`/g, (_, inline) => `<code>${escapeHtml(inline)}</code>`);
  processed = processed.replace(/^\s*[-*] (.*)$/gm, '<li>$1</li>');
  processed = processed.replace(/(<li>.*<\/li>)/gs, match => `<ul>${match}</ul>`);
  processed = processed.replace(/\n{2,}/g, '</p><p>');
  processed = `<p>${processed}</p>`;
  codeBlocks.forEach((block, index) => {
    processed = processed.replace(`%%CODE_BLOCK_${index}%%`, block);
  });
  return processed.replace(/<p>\s*<\/p>/g, '');
}

function detectAndRenderTables(text) {
  // Look for JSON arrays in code blocks (```json [...] ```)
  const jsonCodeBlockPattern = /```json\s*(\[\s*\{[\s\S]*?\}\s*\])\s*```/gi;

  let processedText = text;
  let match;
  let tableCount = 0;

  jsonCodeBlockPattern.lastIndex = 0;

  while ((match = jsonCodeBlockPattern.exec(text)) !== null) {
    try {
      const jsonData = JSON.parse(match[1]);
      if (Array.isArray(jsonData) && jsonData.length > 0 && typeof jsonData[0] === 'object') {
        const tableHtml = createTableFromJson(jsonData);
        processedText = processedText.replace(match[0], tableHtml);
        tableCount++;
      }
    } catch (e) {
      console.warn('Failed to parse JSON table:', e);
    }
  }

  // Also check for plain JSON arrays without code block wrapper
  const plainJsonPattern = /(\[\s*\{[\s\S]*?\}\s*\])/g;
  plainJsonPattern.lastIndex = 0;

  while ((match = plainJsonPattern.exec(processedText)) !== null) {
    // Skip if already inside code block or table
    if (match.index > 0) {
      const beforeMatch = processedText.substring(Math.max(0, match.index - 10), match.index);
      if (beforeMatch.includes('<table') || beforeMatch.includes('```')) {
        continue;
      }
    }

    try {
      const jsonData = JSON.parse(match[1]);
      if (Array.isArray(jsonData) && jsonData.length > 0 && typeof jsonData[0] === 'object') {
        const tableHtml = createTableFromJson(jsonData);
        processedText = processedText.replace(match[0], tableHtml);
        tableCount++;
      }
    } catch (e) {
      // Not valid JSON, leave as is
    }
  }

  return processedText;
}

function createTableFromJson(jsonArray) {
  if (!Array.isArray(jsonArray) || jsonArray.length === 0) {
    return '';
  }

  // Flatten nested objects
  const flattenedArray = jsonArray.map(row => flattenObject(row));

  // Get column headers from first object
  const headers = Object.keys(flattenedArray[0]);

  let tableHtml = '<div class="table-container">';
  tableHtml += `<div class="table-info">📊 ${flattenedArray.length} rows</div>`;
  tableHtml += '<table class="data-table">';

  // Header
  tableHtml += '<thead><tr>';
  headers.forEach(header => {
    const readableHeader = makeHeaderReadable(header);
    tableHtml += `<th>${escapeHtml(readableHeader)}</th>`;
  });
  tableHtml += '</tr></thead>';

  // Body
  tableHtml += '<tbody>';
  flattenedArray.forEach(row => {
    tableHtml += '<tr>';
    headers.forEach(header => {
      const value = row[header] !== null && row[header] !== undefined ? row[header] : '';
      tableHtml += `<td>${escapeHtml(String(value))}</td>`;
    });
    tableHtml += '</tr>';
  });
  tableHtml += '</tbody>';

  tableHtml += '</table></div>';

  return tableHtml;
}

function flattenObject(obj, prefix = '', result = {}) {
  for (let key in obj) {
    if (obj.hasOwnProperty(key)) {
      const newKey = prefix ? `${prefix}.${key}` : key;

      if (obj[key] !== null && typeof obj[key] === 'object' && !Array.isArray(obj[key])) {
        flattenObject(obj[key], newKey, result);
      } else {
        result[newKey] = obj[key];
      }
    }
  }
  return result;
}

function makeHeaderReadable(header) {
  return header
    .split('.')
    .map(part =>
      part.replace(/([A-Z])/g, ' $1')
        .replace(/^./, str => str.toUpperCase())
        .trim()
    )
    .join(' - ');
}

function appendMessage({ role, content, isThinking = false }) {
  const node = messageTemplate.content.firstElementChild.cloneNode(true);
  node.classList.add(role === 'user' ? 'message--user' : 'message--assistant');
  if (isThinking) {
    node.classList.add('message--thinking');
  }
  node.querySelector('.message__author').textContent = role === 'user' ? 'You' : 'Greenplum Assistant';
  const body = node.querySelector('.message__body');

  if (isThinking) {
    body.innerHTML = '<span class="spinner"></span> Synthesising a response…';
  } else {
    body.innerHTML = renderMarkdown(content);
  }

  chatWindow.appendChild(node);
  chatWindow.scrollTop = chatWindow.scrollHeight;
  return node;
}

function updateMeta(config) {
  const mode = config.mode === 'OPENAI' ? 'OpenAI cloud' : 'Local model gateway';
  modelModeEl.textContent = `${mode} • ${config.chatModel}`;
  endpointMetaEl.textContent = `${config.baseUrl} → ${config.embeddingEndpoint} • ${config.embeddingModel}`;
}

async function loadConfig() {
  if (configCache) return configCache;
  try {
    const res = await fetch('/api/chat/config');
    if (!res.ok) throw new Error('Failed to load config');
    configCache = await res.json();
    if (!conversationId && configCache.conversationId) {
      conversationId = configCache.conversationId;
      window.localStorage.setItem(conversationKey, conversationId);
    } else if (!conversationId) {
      conversationId = crypto.randomUUID();
      window.localStorage.setItem(conversationKey, conversationId);
    }
    updateMeta(configCache);
  } catch (err) {
    console.error(err);
    modelModeEl.textContent = 'Unable to resolve model endpoints';
    endpointMetaEl.textContent = '';
  }
  return configCache;
}

async function sendMessage(question) {
  if (!conversationId) {
    conversationId = crypto.randomUUID();
    window.localStorage.setItem(conversationKey, conversationId);
  }
  const version = targetVersionSelect.value;
  const baselines = versionBaselines[version] || ['6.x', '7.x'];

  const payload = {
    question,
    conversationId,
    targetVersion: version === 'postgresql' ? 'postgresql' : version,
    compatibleBaselines: baselines,
    defaultAssumeVersion: version,
    database: databaseSelect.value || null,
    schema: schemaSelect.value || null
  };

  const thinkingNode = appendMessage({ role: 'assistant', isThinking: true });

  try {
    const res = await fetch('/api/chat/message', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    });

    if (!res.ok) {
      throw new Error(`Model response failed (${res.status})`);
    }

    const data = await res.json();
    conversationId = data.conversationId || conversationId;
    window.localStorage.setItem(conversationKey, conversationId);

    thinkingNode.querySelector('.message__body').innerHTML = renderMarkdown(data.answer);
    thinkingNode.classList.remove('message--thinking');
  } catch (error) {
    console.error(error);
    thinkingNode.querySelector('.message__body').innerHTML = `<strong>Request failed.</strong><br>${escapeHtml(error.message)}.`;
    thinkingNode.classList.remove('message--thinking');
    thinkingNode.classList.add('message--error');
  }
}

chatForm.addEventListener('submit', async (event) => {
  event.preventDefault();
  const value = chatInput.value.trim();
  if (!value) return;

  appendMessage({ role: 'user', content: value });
  chatInput.value = '';
  chatInput.style.height = 'auto';
  chatInput.disabled = true;
  chatForm.querySelector('.composer__send').disabled = true;

  await sendMessage(value);

  chatInput.disabled = false;
  chatForm.querySelector('.composer__send').disabled = false;
  chatInput.focus();
});

chatInput.addEventListener('keydown', (event) => {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault();
    chatForm.dispatchEvent(new Event('submit'));
  }
});

chatInput.addEventListener('input', () => {
  chatInput.style.height = 'auto';
  chatInput.style.height = `${Math.min(chatInput.scrollHeight, 170)}px`;
});

// Status panel management
async function updateStatus() {
  try {
    const response = await fetch('/api/status');
    const status = await response.json();

    // Update API status (always green if we got a response)
    const apiStatusEl = document.getElementById('api-status');
    const apiDot = apiStatusEl.querySelector('.status-indicator__dot');
    apiDot.className = 'status-indicator__dot status-indicator__dot--green';
    apiStatusEl.title = 'API: Online';

    // Update database status
    const dbStatusEl = document.getElementById('db-status');
    const dbDot = dbStatusEl.querySelector('.status-indicator__dot');
    if (status.database?.healthy) {
      dbDot.className = 'status-indicator__dot status-indicator__dot--green';
      dbStatusEl.title = `Database: ${status.database.version || 'connected'}`;
    } else {
      dbDot.className = 'status-indicator__dot status-indicator__dot--red';
      dbStatusEl.title = `Database error: ${status.database?.error || 'unknown'}`;
    }

    // Update MCP status
    const mcpStatusEl = document.getElementById('mcp-status');
    const mcpDot = mcpStatusEl.querySelector('.status-indicator__dot');
    if (!status.mcp?.enabled) {
      mcpDot.className = 'status-indicator__dot status-indicator__dot--red';
      mcpStatusEl.title = 'MCP: Disabled';
    } else if (status.mcp?.healthy) {
      const toolCount = status.mcp.toolCount || 0;
      if (toolCount > 0) {
        mcpDot.className = 'status-indicator__dot status-indicator__dot--green';
        mcpStatusEl.title = `MCP: ${toolCount} tools available`;
      } else {
        mcpDot.className = 'status-indicator__dot status-indicator__dot--red';
        mcpStatusEl.title = 'MCP: Connected but no tools';
      }
    } else {
      mcpDot.className = 'status-indicator__dot status-indicator__dot--red';
      mcpStatusEl.title = `MCP: ${status.mcp?.message || 'not connected'}`;
    }

    // Update model status (always green if API responds)
    const modelStatusEl = document.getElementById('model-status');
    const modelDot = modelStatusEl.querySelector('.status-indicator__dot');
    modelDot.className = 'status-indicator__dot status-indicator__dot--green';
    modelStatusEl.title = 'Model: Ready';
  } catch (error) {
    console.error('Failed to fetch status:', error);
    // Set all to error state
    ['api-status', 'db-status', 'mcp-status', 'model-status'].forEach(id => {
      const el = document.getElementById(id);
      const dot = el?.querySelector('.status-indicator__dot');
      if (dot) {
        dot.className = 'status-indicator__dot status-indicator__dot--red';
      }
    });
  }
}

// Load databases from MCP server
async function loadDatabases() {
  try {
    const response = await fetch('/api/mcp/databases');

    if (!response.ok) {
      // Fallback to local database endpoint if MCP not available
      console.warn('MCP database endpoint not available, trying fallback...');
      return loadDatabasesFallback();
    }

    const data = await response.json();

    // Response format: {defaultDatabase: "db1", allowedDatabases: ["db1", "db2"], targetHost: "host:port"}
    databaseSelect.innerHTML = '<option value="">Select database...</option>';

    if (data.allowedDatabases && data.allowedDatabases.length > 0) {
      data.allowedDatabases.forEach(dbName => {
        const option = document.createElement('option');
        option.value = dbName;
        option.textContent = dbName;
        databaseSelect.appendChild(option);
      });

      // Auto-select default database if specified
      if (data.defaultDatabase) {
        databaseSelect.value = data.defaultDatabase;
        await loadSchemas(data.defaultDatabase);
      } else if (data.allowedDatabases.length === 1) {
        // Auto-select if only one database
        databaseSelect.value = data.allowedDatabases[0];
        await loadSchemas(data.allowedDatabases[0]);
      }
    } else {
      databaseSelect.innerHTML = '<option value="">No databases available</option>';
    }
  } catch (error) {
    console.error('Failed to load databases from MCP:', error);
    return loadDatabasesFallback();
  }
}

// Fallback to local database endpoint (deprecated)
async function loadDatabasesFallback() {
  try {
    const response = await fetch('/api/database/list');
    const databases = await response.json();

    databaseSelect.innerHTML = '<option value="">Select database...</option>';
    databases.forEach(db => {
      const option = document.createElement('option');
      option.value = db.name;
      option.textContent = db.name;
      databaseSelect.appendChild(option);
    });

    // Auto-select first database if only one
    if (databases.length === 1) {
      databaseSelect.value = databases[0].name;
      await loadSchemas(databases[0].name);
    }
  } catch (error) {
    console.error('Failed to load databases (fallback):', error);
    databaseSelect.innerHTML = '<option value="">Error loading databases</option>';
  }
}

// Load schemas for selected database from MCP server
async function loadSchemas(database) {
  try {
    schemaSelect.innerHTML = '<option value="">Loading...</option>';
    const response = await fetch(`/api/mcp/schemas?database=${encodeURIComponent(database || '')}`);

    if (!response.ok) {
      // Fallback to local endpoint if MCP not available
      console.warn('MCP schemas endpoint not available, trying fallback...');
      return loadSchemasFallback(database);
    }

    const data = await response.json();

    // Response format: {schemas: ["public", "schema1", ...], error: null}
    schemaSelect.innerHTML = '<option value="">All schemas</option>';

    if (data.schemas && data.schemas.length > 0) {
      data.schemas.forEach(schemaName => {
        const option = document.createElement('option');
        option.value = schemaName;
        option.textContent = schemaName;
        schemaSelect.appendChild(option);
      });

      // Auto-select 'public' if available
      const publicOption = Array.from(schemaSelect.options).find(opt => opt.value === 'public');
      if (publicOption) {
        schemaSelect.value = 'public';
      }
    } else if (data.error) {
      schemaSelect.innerHTML = `<option value="">Error: ${data.error}</option>`;
    }
  } catch (error) {
    console.error('Failed to load schemas from MCP:', error);
    return loadSchemasFallback(database);
  }
}

// Fallback to local schemas endpoint (deprecated)
async function loadSchemasFallback(database) {
  try {
    const response = await fetch(`/api/database/schemas?database=${encodeURIComponent(database || '')}`);
    const schemas = await response.json();

    schemaSelect.innerHTML = '<option value="">All schemas</option>';
    schemas.forEach(schema => {
      const option = document.createElement('option');
      option.value = schema.name;
      option.textContent = schema.name;
      schemaSelect.appendChild(option);
    });

    // Auto-select 'public' if available
    const publicOption = Array.from(schemaSelect.options).find(opt => opt.value === 'public');
    if (publicOption) {
      schemaSelect.value = 'public';
    }
  } catch (error) {
    console.error('Failed to load schemas (fallback):', error);
    schemaSelect.innerHTML = '<option value="">Error loading schemas</option>';
  }
}

// Handle database selection change
databaseSelect.addEventListener('change', async () => {
  const selectedDb = databaseSelect.value;
  if (selectedDb) {
    await loadSchemas(selectedDb);
  } else {
    schemaSelect.innerHTML = '<option value="">Select database first</option>';
  }
});

window.addEventListener('DOMContentLoaded', async () => {
  await loadConfig();
  await updateStatus();
  await loadDatabases();

  // Poll frequently during first 15 seconds to catch MCP connection quickly
  let pollCount = 0;
  const fastPollInterval = setInterval(() => {
    updateStatus();
    pollCount++;
    if (pollCount >= 5) { // After 5 polls (15 seconds), switch to slow polling
      clearInterval(fastPollInterval);
      setInterval(updateStatus, 30000); // Then every 30 seconds
    }
  }, 3000); // Poll every 3 seconds initially

  appendMessage({
    role: 'assistant',
    content: 'Hello! I am ready to help with Greenplum, PostgreSQL, and the underlying RAG knowledge base. Ask me anything about setup, operations, or tuning.'
  });
});

// ===== Settings Modal =====
const settingsModal = document.getElementById('settings-modal');
const settingsBtn = document.getElementById('settings-btn');
const settingsClose = document.getElementById('settings-close');
const settingsSave = document.getElementById('settings-save');
const settingsTest = document.getElementById('settings-test');
const gpMcpUrl = document.getElementById('gp-mcp-url');
const gpMcpApiKey = document.getElementById('gp-mcp-api-key');
const gpMcpShowKey = document.getElementById('gp-mcp-show-key');
const settingsStatus = document.getElementById('settings-status');

// Storage keys
const STORAGE_GP_MCP_URL = 'gp-mcp-url';
const STORAGE_GP_MCP_API_KEY = 'gp-mcp-api-key';

// Load saved settings
function loadSettings() {
  const savedUrl = window.localStorage.getItem(STORAGE_GP_MCP_URL);
  const savedApiKey = window.localStorage.getItem(STORAGE_GP_MCP_API_KEY);

  if (savedUrl) {
    gpMcpUrl.value = savedUrl;
  }
  if (savedApiKey) {
    gpMcpApiKey.value = savedApiKey;
  }
}

// Show settings modal
settingsBtn.addEventListener('click', () => {
  loadSettings();
  settingsModal.style.display = 'flex';
  settingsStatus.style.display = 'none';
});

// Close modal
function closeModal() {
  settingsModal.style.display = 'none';
}

settingsClose.addEventListener('click', closeModal);
settingsModal.querySelector('.modal__overlay').addEventListener('click', closeModal);

// Show/hide API key
gpMcpShowKey.addEventListener('change', () => {
  gpMcpApiKey.type = gpMcpShowKey.checked ? 'text' : 'password';
});

// Test connection
settingsTest.addEventListener('click', async () => {
  const url = gpMcpUrl.value.trim();
  const apiKey = gpMcpApiKey.value.trim();

  if (!url) {
    showSettingsStatus('error', 'Please enter a server URL');
    return;
  }

  if (!apiKey) {
    showSettingsStatus('error', 'Please enter an API key');
    return;
  }

  showSettingsStatus('info', 'Testing connection...');

  try {
    const response = await fetch(`${url}/api/v1/databases`, {
      method: 'GET',
      headers: {
        'Authorization': apiKey
      }
    });

    if (response.ok) {
      const data = await response.json();
      const dbCount = data.allowedDatabases ? data.allowedDatabases.length : 0;
      showSettingsStatus('success', `✓ Connected! Found ${dbCount} database(s) on ${data.targetHost || 'server'}`);
    } else if (response.status === 401 || response.status === 403) {
      showSettingsStatus('error', '✗ Invalid API key or unauthorized');
    } else {
      showSettingsStatus('error', `✗ Connection failed (HTTP ${response.status})`);
    }
  } catch (error) {
    showSettingsStatus('error', `✗ Cannot connect to server: ${error.message}`);
  }
});

// Save settings
settingsSave.addEventListener('click', async () => {
  const url = gpMcpUrl.value.trim();
  const apiKey = gpMcpApiKey.value.trim();

  if (!url) {
    showSettingsStatus('error', 'Please enter a server URL');
    return;
  }

  if (!apiKey) {
    showSettingsStatus('error', 'Please enter an API key');
    return;
  }

  // Save to localStorage
  window.localStorage.setItem(STORAGE_GP_MCP_URL, url);
  window.localStorage.setItem(STORAGE_GP_MCP_API_KEY, apiKey);

  showSettingsStatus('success', '✓ Settings saved! Reloading databases...');

  // Wait a moment for user to see the message
  await new Promise(resolve => setTimeout(resolve, 800));

  // Reload databases with new settings
  await loadDatabases();

  // Close modal after brief delay
  setTimeout(() => {
    closeModal();
    showSettingsStatus('info', 'Settings saved successfully');
  }, 500);
});

// Show status message
function showSettingsStatus(type, message) {
  settingsStatus.className = `settings-status settings-status--${type}`;
  settingsStatus.textContent = message;
  settingsStatus.style.display = 'block';
}

// Override MCP database fetching to use saved settings
const originalFetch = window.fetch;
window.fetch = function(url, options = {}) {
  // Intercept calls to /api/mcp/* to add saved API key if not already present
  if (url && url.includes('/api/mcp/')) {
    const savedApiKey = window.localStorage.getItem(STORAGE_GP_MCP_API_KEY);
    const savedUrl = window.localStorage.getItem(STORAGE_GP_MCP_URL);

    // Add custom header to pass API key to backend
    if (savedApiKey) {
      options.headers = options.headers || {};
      options.headers['X-GP-MCP-API-Key'] = savedApiKey;
    }
    if (savedUrl) {
      options.headers = options.headers || {};
      options.headers['X-GP-MCP-URL'] = savedUrl;
    }
  }

  return originalFetch.call(this, url, options);
};

// Load settings on page load
loadSettings();
