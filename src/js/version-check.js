// 添加动画样式
(function() {
    const style = document.createElement('style');
    style.textContent = `
        @keyframes pulse {
            0%, 100% {
                opacity: 1;
            }
            50% {
                opacity: 0.6;
            }
        }
        .animate-pulse {
            animation: pulse 2s cubic-bezier(0.4, 0, 0.6, 1) infinite;
        }
    `;
    document.head.appendChild(style);
})();

// 获取版本信息
async function fetchVersion(url, errorMessage, options = {}) {
    const response = await fetch(url, options);
    if (!response.ok) {
        throw new Error(errorMessage);
    }
    return await response.text();
}

// 版本检查函数
async function checkForUpdates() {
    let updateCheckFailed = false;
    try {
        // 获取当前版本（本地文件，必须成功）
        const currentVersion = await fetchVersion('/VERSION.txt', '获取当前版本失败', {
            cache: 'no-store'
        });

        // 获取最新版本
        let latestVersion;
        const VERSION_URL = {
            PROXY: 'https://ghfast.top/raw.githubusercontent.com/LibreSpark/LibreTV/main/VERSION.txt',
            DIRECT: 'https://raw.githubusercontent.com/LibreSpark/LibreTV/main/VERSION.txt'
        };
        const FETCH_TIMEOUT = 1500;

        try {
            // 尝试使用代理URL获取最新版本
            const proxyPromise = fetchVersion(VERSION_URL.PROXY, '代理请求失败');
            const timeoutPromise = new Promise((_, reject) =>
                setTimeout(() => reject(new Error('代理请求超时')), FETCH_TIMEOUT)
            );

            latestVersion = await Promise.race([proxyPromise, timeoutPromise]);
            console.log('通过代理服务器获取版本成功');
        } catch (error) {
            console.log('代理请求失败，尝试直接请求:', error.message);
            try {
                // 代理失败后尝试直接获取
                latestVersion = await fetchVersion(VERSION_URL.DIRECT, '获取最新版本失败');
                console.log('直接请求获取版本成功');
            } catch (directError) {
                // 官方 VERSION.txt 可能已下线或网络不通：不再报"检测失败"，
                // 降级为只显示本地版本，站点功能完全不受影响
                console.warn('在线版本检查失败，降级为仅显示本地版本:', directError);
                updateCheckFailed = true;
                latestVersion = currentVersion;
            }
        }

        console.log('当前版本:', currentVersion);
        console.log('最新版本:', latestVersion);

        // 清理版本字符串（移除可能的空格或换行符）
        const cleanCurrentVersion = currentVersion.trim();
        const cleanLatestVersion = latestVersion.trim();

        // 返回版本信息
        return {
            current: cleanCurrentVersion,
            latest: cleanLatestVersion,
            hasUpdate: !updateCheckFailed && parseInt(cleanLatestVersion) > parseInt(cleanCurrentVersion),
            updateCheckFailed: updateCheckFailed,
            currentFormatted: formatVersion(cleanCurrentVersion),
            latestFormatted: formatVersion(cleanLatestVersion)
        };
    } catch (error) {
        console.error('版本检测出错:', error);
        throw error;
    }
}

// 格式化版本号为可读形式 (yyyyMMddhhmm -> yyyy-MM-dd hh:mm)
function formatVersion(versionString) {
    if (!versionString) {
        return '未知版本';
    }

    const cleanedString = versionString.trim();

    if (cleanedString.length === 12) {
        const year = cleanedString.substring(0, 4);
        const month = cleanedString.substring(4, 6);
        const day = cleanedString.substring(6, 8);
        const hour = cleanedString.substring(8, 10);
        const minute = cleanedString.substring(10, 12);
        return `${year}-${month}-${day} ${hour}:${minute}`;
    }

    return cleanedString;
}

// 创建错误版本信息元素（不再显示橙色"检测失败"）
function createErrorVersionElement(errorMessage) {
    const errorElement = document.createElement('p');
    errorElement.className = 'text-gray-500 text-sm mt-1 text-center md:text-left';
    errorElement.innerHTML = `版本: <span class="text-gray-500">(本地版本)</span>`;
    errorElement.title = errorMessage;
    return errorElement;
}

// 添加版本信息到页脚
function addVersionInfoToFooter() {
    checkForUpdates().then(result => {
        if (!result) {
            displayVersionElement(createErrorVersionElement('无法读取版本信息'));
            return;
        }

        const versionElement = document.createElement('p');
        versionElement.className = 'text-gray-500 text-sm mt-1 text-center md:text-left';

        versionElement.innerHTML = `版本: ${result.currentFormatted}`;

        if (result.hasUpdate) {
            versionElement.innerHTML += ` <span class="inline-flex items-center bg-red-600 text-white text-xs px-2 py-0.5 rounded-md ml-1 cursor-pointer animate-pulse font-medium">
                <svg xmlns="http://www.w3.org/2000/svg" class="h-3 w-3 mr-1" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 10V3L4 14h7v7l9-11h-7z" />
                </svg>
                发现新版
            </span>`;

            setTimeout(() => {
                const updateBtn = versionElement.querySelector('span');
                if (updateBtn) {
                    updateBtn.addEventListener('click', () => {
                        window.open('https://github.com/LibreSpark/LibreTV', '_blank');
                    });
                }
            }, 100);
        } else if (result.updateCheckFailed) {
            // 在线检查失败：仅显示本地版本，避免误报"检测失败"
            versionElement.innerHTML = `版本: ${result.currentFormatted} <span class="text-gray-500">(本地版本)</span>`;
        } else {
            versionElement.innerHTML = `版本: ${result.currentFormatted} <span class="text-green-500">(最新版本)</span>`;
        }

        displayVersionElement(versionElement);
    }).catch(error => {
        console.error('版本检测出错:', error);
        displayVersionElement(createErrorVersionElement(`错误信息: ${error.message}`));
    });
}

// 在页脚显示版本元素的辅助函数
function displayVersionElement(element) {
    const footerElement = document.querySelector('.footer p.text-gray-500.text-sm');
    if (footerElement) {
        footerElement.insertAdjacentElement('afterend', element);
    } else {
        const footer = document.querySelector('.footer .container');
        if (footer) {
            footer.querySelector('div').appendChild(element);
        }
    }
}

// 页面加载完成后添加版本信息
document.addEventListener('DOMContentLoaded', addVersionInfoToFooter);
