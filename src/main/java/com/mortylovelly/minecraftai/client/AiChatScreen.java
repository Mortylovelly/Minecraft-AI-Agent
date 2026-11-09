package com.mortylovelly.minecraftai.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class AiChatScreen extends Screen {
    private final List<String> messages = new ArrayList<>();
    private TextFieldWidget messageInput;
    private TextFieldWidget apiKeyInput;
    private ButtonWidget sendButton;
    private ButtonWidget stopButton;
    private ButtonWidget geminiButton;
    private ButtonWidget deepSeekButton;
    private ButtonWidget groqButton;
    private ButtonWidget openRouterButton;
    private ButtonWidget testButton;
    private ButtonWidget newChatButton;
    private ButtonWidget clearChatButton;
    private ButtonWidget logsButton;
    private boolean waiting;
    private String liveStatus = "";
    private long statusAnimationTick;
    private long knownHistorySize = -1;
    private String chatId;
    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;
    private int contentVerticalOffset;
    private long animationStartedAt=System.nanoTime();
    private long closingStartedAt;
    private boolean closing;
    private static final long ANIMATION_DURATION_NANOS=190_000_000L;

    public AiChatScreen() {
        super(Text.literal("Minecraft AI Agent"));
        AiChatHistory.load();
        AiAgentMemory.load();
        chatId = AiChatHistory.getCurrentChatId();
        AiAgentMemory.migrateLegacyToChat(chatId);
        rebuildVisibleHistory();
        syncTaskState();
    }

    @Override
    protected void init() {
        animationStartedAt=System.nanoTime();
        closing=false;
        AiClientConfig.load();
        AiChatHistory.load();
        AiAgentMemory.load();
        chatId = AiChatHistory.getCurrentChatId();
        rebuildVisibleHistory();

        AiAgentStatus.setListener(status -> {
            MinecraftClient client = MinecraftClient.getInstance();
            client.execute(() -> liveStatus = status == null ? "" : status);
        });

        panelWidth = Math.min(760, width - 24);
        panelHeight = Math.min(500, height - 24);
        panelLeft = (width - panelWidth) / 2;
        panelTop = (height - panelHeight) / 2;

        boolean narrowProviders = panelWidth < 452;
        contentVerticalOffset = narrowProviders ? 24 : 0;

        int keyY = panelTop + 31;
        apiKeyInput = new TextFieldWidget(textRenderer, panelLeft + 14, keyY, panelWidth - 140, 20, Text.literal("API key"));
        apiKeyInput.setMaxLength(500);
        apiKeyInput.setText(AiClientConfig.getApiKey());
        apiKeyInput.setPlaceholder(Text.literal("API key выбранного провайдера — хранится локально"));
        addDrawableChild(apiKeyInput);
        addDrawableChild(ButtonWidget.builder(Text.literal("Сохранить"), button -> saveApiKey())
                .dimensions(panelLeft + panelWidth - 112, keyY, 98, 20).build());

        int providerY = panelTop + 58;
        if (narrowProviders) {
            int providerWidth = (panelWidth - 34) / 2;
            geminiButton = addDrawableChild(ButtonWidget.builder(Text.literal("Gemini"), button -> selectProvider("gemini"))
                    .dimensions(panelLeft + 14, providerY, providerWidth, 20).build());
            deepSeekButton = addDrawableChild(ButtonWidget.builder(Text.literal("DeepSeek"), button -> selectProvider("deepseek"))
                    .dimensions(panelLeft + 20 + providerWidth, providerY, providerWidth, 20).build());
            groqButton = addDrawableChild(ButtonWidget.builder(Text.literal("Groq"), button -> selectProvider("groq"))
                    .dimensions(panelLeft + 14, providerY + 24, providerWidth, 20).build());
            openRouterButton = addDrawableChild(ButtonWidget.builder(Text.literal("OpenRouter"), button -> selectProvider("openrouter"))
                    .dimensions(panelLeft + 20 + providerWidth, providerY + 24, providerWidth, 20).build());
        } else {
            geminiButton = addDrawableChild(ButtonWidget.builder(Text.literal("Gemini"), button -> selectProvider("gemini"))
                    .dimensions(panelLeft + 14, providerY, 98, 20).build());
            deepSeekButton = addDrawableChild(ButtonWidget.builder(Text.literal("DeepSeek"), button -> selectProvider("deepseek"))
                    .dimensions(panelLeft + 118, providerY, 98, 20).build());
            groqButton = addDrawableChild(ButtonWidget.builder(Text.literal("Groq"), button -> selectProvider("groq"))
                    .dimensions(panelLeft + 222, providerY, 98, 20).build());
            openRouterButton = addDrawableChild(ButtonWidget.builder(Text.literal("OpenRouter"), button -> selectProvider("openrouter"))
                    .dimensions(panelLeft + 326, providerY, 112, 20).build());
        }

        int actionY = panelTop + 9;
        if (panelWidth >= 560) {
            testButton = addDrawableChild(ButtonWidget.builder(Text.literal("Проверить"), button -> testConnection())
                    .dimensions(panelLeft + panelWidth - 414, actionY, 90, 20).build());
            logsButton = addDrawableChild(ButtonWidget.builder(Text.literal(AiLogOverlay.isOpen() ? "Hide logs" : "Logs"), button -> {
                        AiLogOverlay.toggle();
                        if (AiLogOverlay.isOpen()) beginClose(false);
                    })
                    .dimensions(panelLeft + panelWidth - 320, actionY, 90, 20).build());
            newChatButton = addDrawableChild(ButtonWidget.builder(Text.literal("Новый чат"), button -> newChat())
                    .dimensions(panelLeft + panelWidth - 226, actionY, 100, 20).build());
            clearChatButton = addDrawableChild(ButtonWidget.builder(Text.literal("Очистить"), button -> clearChat())
                    .dimensions(panelLeft + panelWidth - 122, actionY, 108, 20).build());
        } else {
            float fit = Math.min(1.0F, (panelWidth - 28) / 318.0F);
            int testWidth = Math.round(80.0F * fit);
            int logsWidth = Math.round(60.0F * fit);
            int newChatWidth = Math.round(88.0F * fit);
            int clearWidth = Math.round(78.0F * fit);
            int gap = 3;
            int actionWidth = testWidth + logsWidth + newChatWidth + clearWidth + gap * 3;
            int actionX = panelLeft + (panelWidth - actionWidth) / 2;

            testButton = addDrawableChild(ButtonWidget.builder(Text.literal("Проверить"), button -> testConnection())
                    .dimensions(actionX, actionY, testWidth, 20).build());
            actionX += testWidth + gap;
            logsButton = addDrawableChild(ButtonWidget.builder(Text.literal(AiLogOverlay.isOpen() ? "Hide logs" : "Logs"), button -> {
                        AiLogOverlay.toggle();
                        if (AiLogOverlay.isOpen()) beginClose(false);
                    })
                    .dimensions(actionX, actionY, logsWidth, 20).build());
            actionX += logsWidth + gap;
            newChatButton = addDrawableChild(ButtonWidget.builder(Text.literal("Новый чат"), button -> newChat())
                    .dimensions(actionX, actionY, newChatWidth, 20).build());
            actionX += newChatWidth + gap;
            clearChatButton = addDrawableChild(ButtonWidget.builder(Text.literal("Очистить"), button -> clearChat())
                    .dimensions(actionX, actionY, clearWidth, 20).build());
        }

        int inputY = panelTop + panelHeight - 35;
        messageInput = new TextFieldWidget(textRenderer, panelLeft + 14, inputY, panelWidth - 206, 22, Text.literal("Сообщение"));
        messageInput.setMaxLength(16000);
        messageInput.setPlaceholder(Text.literal("Опиши задачу как угодно подробно..."));
        addDrawableChild(messageInput);

        sendButton = addDrawableChild(ButtonWidget.builder(Text.literal("Отправить"), button -> sendMessage())
                .dimensions(panelLeft + panelWidth - 184, inputY, 80, 22).build());
        stopButton = addDrawableChild(ButtonWidget.builder(Text.literal("Стоп"), button -> AiAgentService.cancelCurrentTask())
                .dimensions(panelLeft + panelWidth - 94, inputY, 80, 22).build());

        syncTaskState();
        setInitialFocus(messageInput);
    }

    @Override
    public void tick() {
        super.tick();
        if (closing) {
            if (System.nanoTime()-closingStartedAt>=ANIMATION_DURATION_NANOS) MinecraftClient.getInstance().setScreen(null);
            return;
        }
        syncTaskState();
        long size = AiChatHistory.getAll(chatId).size();
        if (size != knownHistorySize) {
            knownHistorySize = size;
            rebuildVisibleHistory();
        }
    }

    private void rebuildVisibleHistory() {
        messages.clear();
        List<AiChatHistory.Entry> history = AiChatHistory.getAll(chatId);
        if (history.isEmpty()) {
            messages.add("AI: Готов. Я могу работать с миром, предметами, мобами, постройками и командами.");
            return;
        }
        for (AiChatHistory.Entry entry : history) {
            messages.add((entry.role().equals("user") ? "Ты: " : "AI: ") + entry.text());
        }
    }

    private void syncTaskState() {
        waiting = AiAgentService.isBusy() && chatId.equals(AiAgentService.getActiveChatId());
        if (stopButton != null) stopButton.active = waiting;
        if (sendButton != null) sendButton.active = !AiAgentService.isBusy();
        if (testButton != null) testButton.active = !AiAgentService.isBusy();
        if (newChatButton != null) newChatButton.active = !AiAgentService.isBusy();
        if (clearChatButton != null) clearChatButton.active = !AiAgentService.isBusy();
        if (logsButton != null) logsButton.setMessage(Text.literal(AiLogOverlay.isOpen() ? "Hide logs" : "Logs"));
        if (geminiButton != null) geminiButton.active = !AiAgentService.isBusy() && !AiClientConfig.getProvider().equals("gemini");
        if (deepSeekButton != null) deepSeekButton.active = !AiAgentService.isBusy() && !AiClientConfig.getProvider().equals("deepseek");
        if (groqButton != null) groqButton.active = !AiAgentService.isBusy() && !AiClientConfig.getProvider().equals("groq");
        if (openRouterButton != null) openRouterButton.active = !AiAgentService.isBusy() && !AiClientConfig.getProvider().equals("openrouter");
    }

    private void selectProvider(String provider) {
        if (AiAgentService.isBusy()) return;
        saveApiKeySilently();
        AiClientConfig.setProvider(provider);
        apiKeyInput.setText(AiClientConfig.getApiKey());
        messages.add("AI: Выбран провайдер " + providerDisplayName() + ".");
        refreshProviderButtons();
    }

    private void refreshProviderButtons() {
        syncTaskState();
    }

    private void saveApiKeySilently() {
        if (apiKeyInput != null) AiClientConfig.setApiKey(apiKeyInput.getText());
    }

    private void saveApiKey() {
        AiClientConfig.setApiKey(apiKeyInput.getText());
        messages.add(AiClientConfig.hasApiKey()
                ? "AI: " + providerDisplayName() + " API key сохранён локально."
                : "AI: API key очищен.");
    }

    private void newChat() {
        if (AiAgentService.isBusy()) return;
        chatId = AiChatHistory.createChat();
        AiAgentMemory.load();
        liveStatus = "";
        rebuildVisibleHistory();
        knownHistorySize = AiChatHistory.getAll(chatId).size();
    }

    private void clearChat() {
        if (AiAgentService.isBusy()) return;
        AiChatHistory.clear(chatId);
        rebuildVisibleHistory();
        messages.add("AI: История этого чата очищена. Память этого чата сохранена.");
    }

    private void testConnection() {
        if (AiAgentService.isBusy()) return;
        saveApiKeySilently();
        if (!AiClientConfig.hasApiKey()) {
            messages.add("AI: Сначала вставь API key выбранного провайдера.");
            return;
        }
        liveStatus = "Проверяю подключение...";
        messages.add("AI: Проверяю подключение к " + providerDisplayName() + "...");
        syncTaskState();
        AiAgentService.testConnection().whenComplete((ok, throwable) -> {
            MinecraftClient.getInstance().execute(() -> {
                liveStatus = "";
                if (throwable != null) {
                    Throwable cause = deepestCause(throwable);
                    messages.add("AI: Ошибка: " + (cause.getMessage() == null ? cause.toString() : cause.getMessage()));
                } else {
                    messages.add(ok ? "AI: Подключение успешно." : "AI: Провайдер не вернул ожидаемый ответ.");
                }
            });
        });
    }

    private void sendMessage() {
        if (AiAgentService.isBusy() || messageInput == null) return;
        saveApiKeySilently();
        String message = messageInput.getText().trim();
        if (message.isEmpty()) return;
        messages.add("Ты: " + message);
        messageInput.setText("");
        liveStatus = "Анализирую запрос...";
        statusAnimationTick = 0;
        syncTaskState();

        CompletableFuture<String> future = AiAgentService.chat(chatId, message);
        future.whenComplete((reply, throwable) -> MinecraftClient.getInstance().execute(this::syncTaskState));
    }

    private static Throwable deepestCause(Throwable throwable) {
        Throwable current = throwable;
        int depth = 0;
        while (current.getCause() != null && current.getCause() != current && depth++ < 16) current = current.getCause();
        return current;
    }

    private String providerDisplayName() {
        return switch (AiClientConfig.getProvider()) {
            case "gemini" -> "Gemini";
            case "groq" -> "Groq";
            case "openrouter" -> "OpenRouter";
            default -> "DeepSeek";
        };
    }

    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0x66000000);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        float progress=animationProgress();
        context.fill(0,0,width,height,((int)(0x58*progress))<<24);
        var matrices=context.getMatrices();
        matrices.push();
        float scale=0.94F+0.06F*progress;
        matrices.translate(width/2.0F,panelTop+panelHeight/2.0F+(1.0F-progress)*12.0F,0.0F);
        matrices.scale(scale,scale,1.0F);
        matrices.translate(-width/2.0F,-(panelTop+panelHeight/2.0F),0.0F);

        drawRoundedPanel(context,panelLeft,panelTop,panelLeft+panelWidth,panelTop+panelHeight,0xFF6C7783);
        drawRoundedPanel(context,panelLeft+1,panelTop+1,panelLeft+panelWidth-1,panelTop+panelHeight-1,0xFF424B55);
        int headerBottom = 83 + contentVerticalOffset;
        drawRoundedPanel(context,panelLeft+5,panelTop+5,panelLeft+panelWidth-5,panelTop+headerBottom,0xFF353E48);
        context.fill(panelLeft+14,panelTop+headerBottom+1,panelLeft+panelWidth-14,panelTop+headerBottom+2,0xFF697582);

        if (panelWidth >= 560) {
            int titleStartX = panelLeft + 14;
            int firstActionX = panelLeft + panelWidth - 414;
            int titleMaxWidth = Math.max(0, firstActionX - titleStartX - 8);
            if (titleMaxWidth >= 12) {
                context.drawTextWithShadow(textRenderer,
                        textRenderer.trimToWidth(title.getString(), titleMaxWidth),
                        titleStartX, panelTop + 10, 0xFFFFFFFF);
            }
        }

        String statusText = waiting ? buildAnimatedStatus() : providerDisplayName() + " AI Agent";
        String chatText = "Чат: " + chatId.substring(0, Math.min(8, chatId.length()));
        String memoryText = "Память: " + AiAgentMemory.count(chatId);

        int rowLeft = panelLeft + 14;
        int rowWidth = Math.max(0, panelWidth - 28);
        int statusColumn = rowWidth / 3;
        int memoryColumn = Math.min(textRenderer.getWidth(memoryText), rowWidth / 4);
        int columnGap = 8;
        int chatColumn = Math.max(0, rowWidth - statusColumn - memoryColumn - columnGap * 2);
        int chatX = rowLeft + statusColumn + columnGap;
        int memoryX = chatX + chatColumn + columnGap;
        int infoY = panelTop + 96 + contentVerticalOffset;

        if (statusColumn > columnGap) {
            context.drawTextWithShadow(textRenderer,
                    textRenderer.trimToWidth(statusText, statusColumn - columnGap),
                    rowLeft, infoY, waiting ? 0xFF8FE0A0 : 0xFFD0D6DE);
        }
        if (chatColumn > 0) {
            context.drawTextWithShadow(textRenderer,
                    textRenderer.trimToWidth(chatText, chatColumn),
                    chatX, infoY, 0xFFADB6C1);
        }
        if (memoryColumn > 0) {
            context.drawTextWithShadow(textRenderer,
                    textRenderer.trimToWidth(memoryText, memoryColumn),
                    memoryX, infoY, 0xFFD0D6DE);
        }

        int chatTop = panelTop + 116 + contentVerticalOffset;
        int chatBottom = panelTop + panelHeight - 49;
        int maxWidth = panelWidth - 28;
        int y = chatBottom;
        outer:
        for(int i=messages.size()-1;i>=0;i--){
            String[] lines=wrap(messages.get(i),maxWidth);
            for(int line=lines.length-1;line>=0;line--){
                y-=13;
                if(y<chatTop)break outer;
                context.drawTextWithShadow(textRenderer,lines[line],panelLeft+14,y,0xFFF0F2F4);
            }
            y-=7;
        }
        super.render(context,mouseX,mouseY,delta);
        matrices.pop();
    }

    private float animationProgress(){
        long now=System.nanoTime();
        float raw=closing
                ?1.0F-Math.min(1.0F,Math.max(0.0F,(now-closingStartedAt)/(float)ANIMATION_DURATION_NANOS))
                :Math.min(1.0F,Math.max(0.0F,(now-animationStartedAt)/(float)ANIMATION_DURATION_NANOS));
        return raw*raw*(3.0F-2.0F*raw);
    }

    private void beginClose(boolean playSound){
        if(closing)return;
        closing=true;closingStartedAt=System.nanoTime();
        if(playSound)AiUiSound.playClose();
    }

    private static void drawRoundedPanel(DrawContext context,int left,int top,int right,int bottom,int color){
        context.fill(left+4,top,right-4,bottom,color);
        context.fill(left+2,top+1,right-2,bottom-1,color);
        context.fill(left+1,top+2,right-1,bottom-2,color);
        context.fill(left,top+4,right,bottom-4,color);
    }

    @Override
    public boolean mouseClicked(double mouseX,double mouseY,int button){
        if(closing||animationProgress()<0.96F)return true;
        return super.mouseClicked(mouseX,mouseY,button);
    }

    private String buildAnimatedStatus() {
        statusAnimationTick++;
        String base = liveStatus == null || liveStatus.isBlank() ? "ИИ работает..." : liveStatus;
        String[] dots = {"", ".", "..", "..."};
        return "● " + base + dots[(int) ((statusAnimationTick / 8) % dots.length)];
    }

    private String[] wrap(String text, int maxWidth) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (textRenderer.getWidth(candidate) <= maxWidth) current = new StringBuilder(candidate);
            else {
                if (!current.isEmpty()) result.add(current.toString());
                current = new StringBuilder(word);
            }
        }
        if (!current.isEmpty()) result.add(current.toString());
        if (result.isEmpty()) result.add("");
        return result.toArray(String[]::new);
    }

    @Override
    public boolean keyPressed(int keyCode,int scanCode,int modifiers){
        if(closing)return true;
        if(keyCode==257&&messageInput!=null&&messageInput.isFocused()){sendMessage();return true;}
        if(keyCode==256){beginClose(true);return true;}
        return super.keyPressed(keyCode,scanCode,modifiers);
    }

    @Override
    public void close(){beginClose(true);}

    @Override
    public void removed(){
        AiAgentStatus.clearListener();
        if(AiLogOverlay.isOpen())AiLogOverlay.onParentScreenClosed();
    }
}
