const { app, BrowserWindow, dialog, shell } = require('electron');
const path = require('path');
const { spawn } = require('child_process');
const http = require('http');

const PORT = 17890;
const PASSWORD = '2414tv';
let serverProcess = null;
let mainWindow = null;

function serverPath() {
  return path.join(__dirname, '..', 'src', 'server.mjs');
}

function startServer() {
  return new Promise((resolve, reject) => {
    serverProcess = spawn(process.execPath, [serverPath()], {
      env: {
        ...process.env,
        ELECTRON_RUN_AS_NODE: '1',
        NODE_ENV: 'production',
        PORT: String(PORT),
        PASSWORD: PASSWORD,
        CORS_ORIGIN: '*',
        REQUEST_TIMEOUT: '8000',
        MAX_RETRIES: '2'
      },
      stdio: ['ignore', 'pipe', 'pipe'],
      windowsHide: true,
      detached: false
    });

    let stderr = '';
    serverProcess.stderr.on('data', (chunk) => {
      stderr += chunk.toString();
      if (stderr.length > 4000) stderr = stderr.slice(-4000);
    });
    serverProcess.stdout.on('data', () => {});
    serverProcess.on('error', reject);
    serverProcess.on('exit', (code) => {
      if (!app.isQuitting && code !== 0) {
        console.error('内置服务异常退出:', code, stderr);
      }
    });

    const waitReady = (attempts) => {
      const req = http.get(`http://127.0.0.1:${PORT}/`, (res) => {
        res.resume();
        resolve();
      });
      req.on('error', () => {
        if (attempts <= 0) {
          reject(new Error('内置服务启动超时' + (stderr ? '\n' + stderr : '')));
        } else {
          setTimeout(() => waitReady(attempts - 1), 250);
        }
      });
      req.setTimeout(800, () => req.destroy());
    };
    waitReady(60);
  });
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1360,
    height: 860,
    minWidth: 900,
    minHeight: 600,
    title: '山影视',
    icon: path.join(__dirname, '..', 'build', 'icons', 'icon.ico'),
    autoHideMenuBar: true,
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true,
      webSecurity: false,
      allowRunningInsecureContent: true
    }
  });

  mainWindow.setMenuBarVisibility(false);
  mainWindow.loadURL(`http://127.0.0.1:${PORT}/`);

  // 外部链接用系统浏览器打开
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    if (url.startsWith('http://127.0.0.1')) return { action: 'allow' };
    shell.openExternal(url);
    return { action: 'deny' };
  });

  mainWindow.on('closed', () => { mainWindow = null; });
}

app.whenReady().then(async () => {
  try {
    await startServer();
    createWindow();
  } catch (err) {
    dialog.showErrorBox('山影视启动失败', String(err && err.message ? err.message : err));
    app.quit();
  }
});

app.on('window-all-closed', () => {
  app.isQuitting = true;
  if (serverProcess) {
    try { serverProcess.kill(); } catch (e) {}
  }
  app.quit();
});

app.on('before-quit', () => {
  app.isQuitting = true;
  if (serverProcess) {
    try { serverProcess.kill(); } catch (e) {}
  }
});
