'use strict';
// Ponte minimo e controllato tra l'interfaccia e il processo principale.
const { contextBridge, ipcRenderer } = require('electron');

const INVOKE = new Set([
  'tab:new', 'tab:close', 'tab:activate', 'tab:navigate', 'tab:back', 'tab:forward',
  'tab:reload', 'tab:stop', 'tab:home', 'tab:zoom-reset', 'ui:chrome-height', 'ui:panel',
  'find:start', 'find:stop', 'settings:get', 'settings:set', 'net:check', 'filters:update', 'data:clear',
  'privacy:evaluate', 'tab:open-test',
]);
const EVENTS = new Set(['tabs', 'find', 'toast', 'focus-address', 'open-find', 'open-panel', 'settings', 'fullscreen']);

contextBridge.exposeInMainWorld('angerona', {
  invoke(channel, ...args) {
    if (!INVOKE.has(channel)) return Promise.reject(new Error('Canale non permesso'));
    return ipcRenderer.invoke(channel, ...args);
  },
  on(event, fn) {
    if (!EVENTS.has(event) || typeof fn !== 'function') return;
    ipcRenderer.on(`ui:${event}`, (_e, data) => fn(data));
  },
});
