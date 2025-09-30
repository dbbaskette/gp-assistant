const chatWindow = document.getElementById('chat-window');
const messageTemplate = document.getElementById('message-template');
const chatForm = document.getElementById('chat-form');
const chatInput = document.getElementById('chat-input');
const targetVersionSelect = document.getElementById('target-version');
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
    defaultAssumeVersion: version
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

window.addEventListener('DOMContentLoaded', async () => {
  await loadConfig();
  appendMessage({
    role: 'assistant',
    content: 'Hello! I am ready to help with Greenplum, PostgreSQL, and the underlying RAG knowledge base. Ask me anything about setup, operations, or tuning.'
  });
});
