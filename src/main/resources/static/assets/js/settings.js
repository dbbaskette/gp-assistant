// ===== MCP Server Settings Management =====

// DOM Elements
const settingsModal = document.getElementById('settings-modal');
const settingsBtn = document.getElementById('settings-btn');
const settingsClose = document.getElementById('settings-close');
const mcpServersList = document.getElementById('mcp-servers-list');
const serverFormSection = document.getElementById('server-form-section');
const serverForm = document.getElementById('server-form');
const addServerBtn = document.getElementById('add-server-btn');
const cancelFormBtn = document.getElementById('cancel-form-btn');
const testConnectionBtn = document.getElementById('test-connection-btn');
const toggleApiKeyBtn = document.getElementById('toggle-api-key');
const serverFormStatus = document.getElementById('server-form-status');
const formTitle = document.getElementById('form-title');

// Form inputs
const serverIdInput = document.getElementById('server-id');
const serverNameInput = document.getElementById('server-name');
const serverUrlInput = document.getElementById('server-url');
const serverApiKeyInput = document.getElementById('server-api-key');
const serverDescriptionInput = document.getElementById('server-description');

// API base path
const API_BASE = '/api/mcp/servers';

// ===== Modal Management =====

settingsBtn.addEventListener('click', async () => {
  await openSettings();
});

settingsClose.addEventListener('click', closeSettings);
settingsModal.querySelector('.modal__overlay').addEventListener('click', closeSettings);

async function openSettings() {
  settingsModal.style.display = 'flex';
  await loadServers();
  showServersList();
}

function closeSettings() {
  settingsModal.style.display = 'none';
  resetForm();
}

// ===== Server List Management =====

async function loadServers() {
  try {
    const response = await fetch(API_BASE);
    if (!response.ok) throw new Error('Failed to load servers');

    const servers = await response.json();
    renderServersList(servers);
  } catch (error) {
    console.error('Error loading MCP servers:', error);
    mcpServersList.innerHTML = `
      <div class="error-message">
        Failed to load MCP servers: ${error.message}
      </div>
    `;
  }
}

function renderServersList(servers) {
  if (servers.length === 0) {
    mcpServersList.innerHTML = `
      <div class="empty-state">
        <p>No MCP servers configured.</p>
        <p><small>Click "Add Server" to configure your first MCP server.</small></p>
      </div>
    `;
    return;
  }

  mcpServersList.innerHTML = servers.map(server => `
    <div class="mcp-server-card ${server.active ? 'mcp-server-card--active' : ''}" data-id="${server.id}">
      <div class="mcp-server-card__header">
        <div class="mcp-server-card__title">
          <span class="mcp-server-card__status ${getStatusClass(server.status)}">●</span>
          <strong>${escapeHtml(server.name)}</strong>
          ${server.active ? '<span class="badge badge--active">ACTIVE</span>' : ''}
        </div>
        <div class="mcp-server-card__actions">
          ${!server.active ? `
            <button class="btn btn--small btn--primary" onclick="activateServer('${server.id}')">
              Set Active
            </button>
          ` : `
            <button class="btn btn--small btn--secondary" onclick="deactivateServer('${server.id}')">
              Deactivate
            </button>
          `}
          <button class="btn btn--small btn--secondary" onclick="testServer('${server.id}')">
            Test
          </button>
          <button class="btn btn--small btn--secondary" onclick="editServer('${server.id}')">
            Edit
          </button>
          <button class="btn btn--small btn--danger" onclick="deleteServer('${server.id}', '${escapeHtml(server.name)}')">
            Delete
          </button>
        </div>
      </div>
      <div class="mcp-server-card__body">
        <div class="mcp-server-card__info">
          <div><strong>URL:</strong> ${escapeHtml(server.url)}</div>
          ${server.description ? `<div><strong>Description:</strong> ${escapeHtml(server.description)}</div>` : ''}
          <div><strong>Status:</strong> ${server.status} ${server.statusMessage ? `(${escapeHtml(server.statusMessage)})` : ''}</div>
          <div><strong>Tools:</strong> ${server.toolCount}</div>
          ${server.lastTestedAt ? `<div><small>Last tested: ${formatDate(server.lastTestedAt)}</small></div>` : ''}
        </div>
      </div>
    </div>
  `).join('');
}

function getStatusClass(status) {
  switch(status) {
    case 'connected': return 'status--connected';
    case 'error': return 'status--error';
    default: return 'status--disconnected';
  }
}

// ===== Form Management =====

addServerBtn.addEventListener('click', () => {
  resetForm();
  formTitle.textContent = 'Add MCP Server';
  showServerForm();
});

cancelFormBtn.addEventListener('click', () => {
  resetForm();
  showServersList();
});

toggleApiKeyBtn.addEventListener('click', () => {
  const input = serverApiKeyInput;
  input.type = input.type === 'password' ? 'text' : 'password';
  toggleApiKeyBtn.textContent = input.type === 'password' ? '👁️' : '🙈';
});

function showServerForm() {
  mcpServersList.style.display = 'none';
  serverFormSection.style.display = 'block';
  addServerBtn.style.display = 'none';
}

function showServersList() {
  mcpServersList.style.display = 'block';
  serverFormSection.style.display = 'none';
  addServerBtn.style.display = 'inline-block';
}

function resetForm() {
  serverForm.reset();
  serverIdInput.value = '';
  serverFormStatus.style.display = 'none';
}

// ===== Server Actions =====

window.editServer = async function(id) {
  try {
    const response = await fetch(`${API_BASE}/${id}`);
    if (!response.ok) throw new Error('Failed to load server');

    const server = await response.json();

    serverIdInput.value = server.id;
    serverNameInput.value = server.name;
    serverUrlInput.value = server.url;
    serverApiKeyInput.value = ''; // Don't show encrypted key
    serverApiKeyInput.placeholder = '(unchanged)';
    serverDescriptionInput.value = server.description || '';

    formTitle.textContent = 'Edit MCP Server';
    showServerForm();
  } catch (error) {
    console.error('Error loading server:', error);
    alert('Failed to load server: ' + error.message);
  }
};

window.deleteServer = async function(id, name) {
  if (!confirm(`Are you sure you want to delete "${name}"?`)) return;

  try {
    const response = await fetch(`${API_BASE}/${id}`, { method: 'DELETE' });
    if (!response.ok) {
      const error = await response.json();
      throw new Error(error.error || 'Delete failed');
    }

    await loadServers();
    showFormStatus('success', `Server "${name}" deleted successfully`);
  } catch (error) {
    console.error('Error deleting server:', error);
    alert('Failed to delete server: ' + error.message);
  }
};

window.activateServer = async function(id) {
  try {
    const response = await fetch(`${API_BASE}/${id}/activate`, { method: 'POST' });
    if (!response.ok) throw new Error('Failed to activate server');

    await loadServers();
    showFormStatus('success', 'Server activated');

    // Reload databases from new active server
    if (typeof loadDatabases === 'function') {
      await loadDatabases();
    }
  } catch (error) {
    console.error('Error activating server:', error);
    alert('Failed to activate server: ' + error.message);
  }
};

window.deactivateServer = async function(id) {
  try {
    const response = await fetch(`${API_BASE}/deactivate-all`, { method: 'POST' });
    if (!response.ok) throw new Error('Failed to deactivate server');

    await loadServers();
    showFormStatus('success', 'Server deactivated');
  } catch (error) {
    console.error('Error deactivating server:', error);
    alert('Failed to deactivate server: ' + error.message);
  }
};

window.testServer = async function(id) {
  showFormStatus('info', 'Testing connection...');

  try {
    const response = await fetch(`${API_BASE}/${id}/test`, { method: 'POST' });
    const result = await response.json();

    if (result.success) {
      showFormStatus('success', `✓ Connected! Found ${result.toolCount} tool(s)`);
      await loadServers(); // Refresh status
    } else {
      showFormStatus('error', `✗ ${result.message}`);
    }
  } catch (error) {
    console.error('Error testing connection:', error);
    showFormStatus('error', `✗ Test failed: ${error.message}`);
  }
};

// ===== Form Submission =====

testConnectionBtn.addEventListener('click', async (e) => {
  e.preventDefault();

  const url = serverUrlInput.value.trim();
  const apiKey = serverApiKeyInput.value.trim();

  if (!url || !apiKey) {
    showFormStatus('error', 'URL and API Key are required for testing');
    return;
  }

  showFormStatus('info', 'Testing connection...');

  try {
    const response = await fetch(`${API_BASE}/test`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ url, apiKey })
    });

    const result = await response.json();

    if (result.success) {
      showFormStatus('success', `✓ Connected! Found ${result.toolCount} tool(s)`);
    } else {
      showFormStatus('error', `✗ ${result.message}`);
    }
  } catch (error) {
    console.error('Error testing connection:', error);
    showFormStatus('error', `✗ Test failed: ${error.message}`);
  }
});

serverForm.addEventListener('submit', async (e) => {
  e.preventDefault();

  const id = serverIdInput.value;
  const name = serverNameInput.value.trim();
  const url = serverUrlInput.value.trim();
  const apiKey = serverApiKeyInput.value.trim();
  const description = serverDescriptionInput.value.trim();

  if (!name || !url) {
    showFormStatus('error', 'Name and URL are required');
    return;
  }

  // API key required for new servers, optional for updates
  if (!id && !apiKey) {
    showFormStatus('error', 'API Key is required');
    return;
  }

  showFormStatus('info', 'Saving...');

  try {
    const payload = { name, url, apiKey, description };
    const method = id ? 'PUT' : 'POST';
    const endpoint = id ? `${API_BASE}/${id}` : API_BASE;

    const response = await fetch(endpoint, {
      method,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    });

    if (!response.ok) {
      const error = await response.json();
      throw new Error(error.error || 'Save failed');
    }

    showFormStatus('success', `✓ Server ${id ? 'updated' : 'created'} successfully`);

    setTimeout(async () => {
      await loadServers();
      showServersList();
      resetForm();
    }, 1000);

  } catch (error) {
    console.error('Error saving server:', error);
    showFormStatus('error', `✗ ${error.message}`);
  }
});

// ===== Utility Functions =====

function showFormStatus(type, message) {
  serverFormStatus.className = `settings-status settings-status--${type}`;
  serverFormStatus.textContent = message;
  serverFormStatus.style.display = 'block';

  if (type === 'success') {
    setTimeout(() => {
      serverFormStatus.style.display = 'none';
    }, 3000);
  }
}

function escapeHtml(text) {
  const map = {
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#039;'
  };
  return String(text || '').replace(/[&<>"']/g, m => map[m]);
}

function formatDate(dateString) {
  try {
    const date = new Date(dateString);
    return date.toLocaleString();
  } catch (e) {
    return dateString;
  }
}

// ===== Initialize =====
console.log('MCP Server Settings loaded');
