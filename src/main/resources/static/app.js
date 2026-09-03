const $ = selector => document.querySelector(selector);
const $$ = selector => document.querySelectorAll(selector);
let busy = false;
const confirmingDocuments = new Set();
let documentsCache = [];
let processingDocumentId = null;

const toast = message => {
  const element = $('#toast');
  element.textContent = message;
  element.classList.add('show');
  setTimeout(() => element.classList.remove('show'), 2400);
};

function switchView(view) {
  $$('nav button').forEach(button => button.classList.toggle('active', button.dataset.view === view));
  $$('.view').forEach(element => element.classList.remove('active'));
  $(`#${view}View`).classList.add('active');
  $('#pageTitle').textContent = view === 'chat' ? '知识问答' : '知识库';
  $('#subtitle').textContent = view === 'chat' ? '基于你的资料进行可靠回答' : '管理与索引本地资料';
  if (view === 'docs') loadDocs();
}

$$('nav button').forEach(button => button.onclick = () => switchView(button.dataset.view));
$('#newChat').onclick = () => {
  switchView('chat');
  $('#messages').innerHTML = hero;
};

const hero = $('#messages').innerHTML;
$$('.prompts button').forEach(button => button.onclick = () => {
  $('#question').value = button.textContent.replace('↗', '').trim();
  $('#chatForm').requestSubmit();
});

function escapeHtml(value) {
  return value.replace(/[&<>"]/g, character => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;'
  })[character]);
}

function addBubble(type, html) {
  if ($('.hero')) $('#messages').innerHTML = '';
  const element = document.createElement('div');
  element.className = `bubble ${type}`;
  element.innerHTML = `<div class="who">${type === 'user' ? 'YOU' : 'LOCALMIND'}</div><div class="body">${html}</div>`;
  $('#messages').append(element);
  $('#messages').scrollTop = $('#messages').scrollHeight;
  return element;
}

$('#chatForm').onsubmit = async event => {
  event.preventDefault();
  const question = $('#question').value.trim();
  if (!question || busy) return;
  busy = true;
  $('#send').disabled = true;
  addBubble('user', escapeHtml(question));
  $('#question').value = '';
  const waiting = addBubble('ai', '正在检索知识库并生成回答…');
  try {
    const response = await fetch('/api/chat', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({message: question})
    });
    const data = await response.json();
    if (!response.ok) throw Error(data.message || '请求失败');
    const sources = data.sources?.length
      ? `<details class="sources"><summary>查看 ${data.sources.length} 个引用来源</summary>${data.sources.map(source => `<div class="source"><b>[资料 ${source.index}] ${escapeHtml(source.name || '未知来源')}</b><br>${escapeHtml(source.excerpt)} · ${(source.score * 100).toFixed(0)}% 匹配</div>`).join('')}</details>`
      : '';
    waiting.querySelector('.body').innerHTML = escapeHtml(data.answer) + sources;
  } catch (error) {
    waiting.querySelector('.body').textContent = `出错了：${error.message}`;
  } finally {
    busy = false;
    $('#send').disabled = false;
    $('#question').focus();
  }
};

$('#question').onkeydown = event => {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault();
    $('#chatForm').requestSubmit();
  }
};
$('#question').oninput = event => {
  event.target.style.height = 'auto';
  event.target.style.height = `${event.target.scrollHeight}px`;
};

const statusLabels = {
  QUEUED: '○ 等待解析',
  PENDING: '○ 待确认',
  PROCESSING: '◌ 处理中',
  READY: '● 已就绪',
  FAILED: '× 失败'
};

function displayedStatus(document) {
  if (document.id === processingDocumentId) return 'PROCESSING';
  if (confirmingDocuments.has(document.id)) return 'QUEUED';
  return document.status;
}

async function loadDocs() {
  try {
    const response = await fetch('/api/documents');
    const documents = await response.json();
    documentsCache = documents;
    $('#docCount').textContent = documents.length;
    const confirmableCount = documents.filter(document => document.confirmable).length;
    $('#confirmAll').hidden = confirmableCount === 0;
    $('#confirmAll').disabled = confirmingDocuments.size > 0;
    $('#confirmAll').textContent = confirmingDocuments.size > 0
      ? `解析队列剩余 ${confirmingDocuments.size} 个文档…`
      : `统一解析（${confirmableCount}）`;
    $('#docList').innerHTML = documents.length
      ? documents.map(document => `<div class="docrow">
          <div class="docname"><b>${escapeHtml(document.name)}</b><small>${formatSize(document.sizeBytes)}${document.errorMessage ? ` · ${escapeHtml(document.errorMessage)}` : ''}</small></div>
          <span>${document.chunkCount}</span>
          <span class="pill ${displayedStatus(document)}">${statusLabels[displayedStatus(document)] || displayedStatus(document)}</span>
          <span>${new Date(document.createdAt).toLocaleString('zh-CN')}</span>
          <div class="actions"><button class="del" onclick="removeDoc(${document.id})" ${confirmingDocuments.has(document.id) ? 'disabled' : ''}>×</button></div>
        </div>`).join('')
      : '<div class="empty">还没有文档，上传第一份资料开始使用</div>';
  } catch (error) {
    toast('无法读取文档列表');
  }
}

const formatSize = size => size < 1024
  ? `${size} B`
  : size < 1048576 ? `${(size / 1024).toFixed(1)} KB` : `${(size / 1048576).toFixed(1)} MB`;

async function upload(files) {
  const selectedFiles = Array.from(files || []);
  if (!selectedFiles.length) return;
  toast(`正在上传 ${selectedFiles.length} 个文件…`);
  const form = new FormData();
  selectedFiles.forEach(file => form.append('file', file));
  try {
    const response = await fetch('/api/documents', {method: 'POST', body: form});
    const data = await response.json();
    if (!response.ok) throw Error(data.message || '上传失败');
    toast(`${data.length} 个文件上传完成，请在列表中确认解析`);
    $('#file').value = '';
    loadDocs();
  } catch (error) {
    toast(error.message || '上传失败');
  }
}
async function confirmAll() {
  const documents = documentsCache.filter(document => document.confirmable);
  if (!documents.length || confirmingDocuments.size > 0
      || !confirm(`将统一解析 ${documents.length} 个文档，是否继续？`)) return;
  documents.forEach(document => confirmingDocuments.add(document.id));
  let succeeded = 0;
  let failed = 0;
  toast(`开始解析 ${documents.length} 个文档…`);
  await loadDocs();
  for (const document of documents) {
    processingDocumentId = document.id;
    await loadDocs();
    try {
      const response = await fetch(`/api/documents/${document.id}/confirm`, {method: 'POST'});
      const data = await response.json();
      if (!response.ok) throw Error(data.message || '解析失败');
      data.status === 'READY' ? succeeded++ : failed++;
    } catch (error) {
      failed++;
    } finally {
      confirmingDocuments.delete(document.id);
      processingDocumentId = null;
      await loadDocs();
    }
  }
  toast(failed
    ? `解析完成：${succeeded} 个成功，${failed} 个失败`
    : `解析完成：${succeeded} 个文档已就绪`);
}
async function removeDoc(id) {
  if (!confirm('删除这条文档记录？')) return;
  try {
    const response = await fetch(`/api/documents/${id}`, {method: 'DELETE'});
    if (!response.ok) {
      const data = await response.json();
      throw Error(data.message || '删除失败');
    }
    toast('文档记录已删除');
    loadDocs();
  } catch (error) {
    toast(error.message || '删除失败');
  }
}

$('#file').onchange = event => upload(event.target.files);
const drop = $('#drop');
['dragenter', 'dragover'].forEach(name => drop.addEventListener(name, event => {
  event.preventDefault();
  drop.classList.add('drag');
}));
['dragleave', 'drop'].forEach(name => drop.addEventListener(name, event => {
  event.preventDefault();
  drop.classList.remove('drag');
}));
drop.ondrop = event => upload(event.dataTransfer.files);
$('#confirmAll').onclick = confirmAll;
window.removeDoc = removeDoc;
loadDocs();
