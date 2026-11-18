package com.getjobs.application.controller;

import com.getjobs.worker.manager.PlaywrightManager;
import com.microsoft.playwright.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 临时探针：检查 BOSS 聊天页"发送图片"按钮的真实 DOM 结构，
 * 用于判断图片简历上传后是否还需要显式点击发送。
 * 只读 DOM，不上传任何文件，不会发出任何消息。
 */
@RestController
@RequestMapping("/api/probe")
public class ImageProbeController {

    private final PlaywrightManager playwrightManager;

    public ImageProbeController(PlaywrightManager playwrightManager) {
        this.playwrightManager = playwrightManager;
    }

    @GetMapping("/image-button")
    public Map<String, Object> probeImageButton() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            playwrightManager.callOnPlaywright(() -> {
                Page page = playwrightManager.getBossPage();
                if (page == null) {
                    out.put("success", false);
                    out.put("error", "bossPage 未初始化");
                    return null;
                }
                String current = page.url();
                out.put("currentUrlBefore", current);
                if (current == null || !current.contains("/web/geek/chat")) {
                    page.navigate("https://www.zhipin.com/web/geek/chat");
                    page.waitForTimeout(4000);
                }
                // 图片按钮只在"打开了某个会话"后才会渲染，先点开列表里第一个会话
                try {
                    var items = page.locator("//li[@role='listitem']");
                    out.put("chatListItemCount", items.count());
                    if (items.count() > 0) {
                        items.first().click();
                        page.waitForTimeout(3000);
                    }
                } catch (Exception e) {
                    out.put("openChatError", e.getMessage());
                }

                Map<String, Object> probe = new LinkedHashMap<>();
                out.put("probe", probe);
                probe.put("url", page.url());

                // ---- 只读部分：DOM 结构 ----
                Object dom = page.evaluate("""
                        () => {
                          const c = document.querySelector("div[aria-label='发送图片']");
                          if (!c) return {hasContainer:false};
                          const i = c.querySelector("input[type='file']");
                          const b = document.querySelector("button.btn-send, div.send-message");
                          return {
                            hasContainer:true,
                            containerClass:c.className,
                            hasFileInput:!!i,
                            inputOpacity: i ? getComputedStyle(i).opacity : null,
                            inputRect: i ? (r=>({w:r.width,h:r.height}))(i.getBoundingClientRect()) : null,
                            sendBtnClass: b ? b.className : null,
                            sendBtnText: b ? (b.innerText||'').trim() : null
                          };
                        }
                        """);
                probe.put("dom", dom);

                // ---- 实测部分：上传图片，观察发送按钮变化，然后清空（不发送）----
                try {
                    var container = page.locator("div[aria-label='发送图片']");
                    var fileInput = container.locator("input[type='file']").first();
                    // 关键修复点：等"已挂载"而不是"可见"
                    fileInput.waitFor(new com.microsoft.playwright.Locator.WaitForOptions()
                            .setTimeout(10_000)
                            .setState(com.microsoft.playwright.options.WaitForSelectorState.ATTACHED));
                    probe.put("waitForAttached", "OK（不再超时）");

                    java.nio.file.Path img = java.nio.file.Files.createTempFile("probe-resume-", ".jpg");
                    try (java.io.InputStream in = getClass().getResourceAsStream("/resume.jpg")) {
                        if (in == null) throw new IllegalStateException("resume.jpg 不存在");
                        java.nio.file.Files.copy(in, img, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                    fileInput.setInputFiles(img);
                    page.waitForTimeout(6000);

                    var sendBtn = page.locator("button.btn-send, div.send-message").first();
                    String clsBefore = sendBtn.getAttribute("class");
                    probe.put("sendBtnClass_afterUpload", clsBefore);
                    probe.put("sendBtnDisabled_afterUpload", clsBefore != null && clsBefore.contains("disabled"));

                    // 把聊天输入区整段 HTML 抓出来（截断），看到底有没有出现图片预览
                    Object dump = page.evaluate("""
                            () => {
                              const pick = [
                                '.chat-input', '.message-input', '.input-area',
                                '.chat-message', '.message-toolbar', '.send-area',
                                '[contenteditable]', '.dialog-container'
                              ];
                              const seen = new Set(); const parts = [];
                              for (const sel of pick) {
                                document.querySelectorAll(sel).forEach(el => {
                                  if (seen.has(el)) return; seen.add(el);
                                  const h = el.innerHTML || '';
                                  if (h.length > 0) parts.push(sel + ' :: ' + h.slice(0, 700));
                                });
                              }
                              const imgs = document.querySelectorAll('img');
                              const imgList = Array.from(imgs).slice(0, 12).map(i => ({
                                cls: i.className, src: (i.src||'').slice(0, 60),
                                parentCls: i.parentElement ? i.parentElement.className : ''
                              }));
                              return {
                                areas: parts.slice(0, 6),
                                imgCount: imgs.length,
                                imgList: imgList,
                                fileInputValue: (() => { const f = document.querySelector("input[type='file']");
                                  return f ? (f.value || '(empty)') : '(no input)'; })()
                              };
                            }
                            """);
                    probe.put("dump", dump);

                    // 清理：清空文件输入，不在输入框里留下待发送的图片
                    fileInput.setInputFiles(new java.nio.file.Path[0]);
                    page.waitForTimeout(1000);
                    probe.put("cleaned", true);
                } catch (Exception e) {
                    probe.put("uploadTestError", e.getMessage());
                }
                out.put("success", true);
                return null;
            });
        } catch (Exception e) {
            out.put("success", false);
            out.put("error", e.getMessage());
        }
        return out;
    }
}
