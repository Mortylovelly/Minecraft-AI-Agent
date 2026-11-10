package com.mortylovelly.minecraftai.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.Window;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;

import java.nio.DoubleBuffer;
import java.util.ArrayList;
import java.util.List;

public final class AiLogOverlay {
    private static final int BASE_WIDTH=360, BASE_HEIGHT=210, COLLAPSED_HEIGHT=24, HEADER_HEIGHT=29;
    private static final float MIN_PANEL_SCALE=0.25F, MAX_PANEL_SCALE=1.6F;
    private static boolean open, visible, collapsed, dragging, resizing, pointerCaptured;
    private static int x=-1, y=-1, width=300, height=175;
    private static int dragMouseX, dragMouseY, dragStartX, dragStartY;
    private static float resizeStartScale;
    private static int filter=AiLogClient.FILTER_ALL, scrollOffset;
    private static long cachedRevision=-1L;
    private static int cachedWidth=-1, cachedFilter=-1;
    private static final List<DisplayLine> CACHED_LINES=new ArrayList<>();
    private static String copyNotice="";
    private static long copyNoticeUntil;
    private static float animationFrom, animationTo=1.0F;
    private static long animationStartedAt;
    private static final long ANIMATION_DURATION_NANOS=190_000_000L;

    private AiLogOverlay(){}
    private record DisplayLine(String text,int color){}

    public static boolean isOpen(){return open;}
    public static void toggle(){setOpen(!open);}
    public static void hide(){setOpen(false);}

    private static void setOpen(boolean value){
        float current=animationProgress();
        open=value;
        visible=value||current>0.005F;
        animationFrom=current;
        animationTo=value?1.0F:0.0F;
        animationStartedAt=System.nanoTime();
        dragging=false;
        resizing=false;
        pointerCaptured=false;
        if(value){
            collapsed=false;
            scrollOffset=0;
            cachedRevision=-1L;
        }else{
            AiUiSound.playClose();
        }
    }

    public static void onParentScreenClosed(){
        if(!open)return;
        visible=true;
        animationFrom=0.0F;
        animationTo=1.0F;
        animationStartedAt=System.nanoTime();
        dragging=false;
        resizing=false;
        pointerCaptured=false;
    }

    public static void tick(MinecraftClient client){
        // Deliberately do not lock or unlock the cursor here.
        // Cursor visibility is owned by the user's separate G-key mouse mod.
        animationProgress();
    }

    private static float animationProgress(){
        if(!visible)return 0.0F;
        float raw=Math.min(1.0F,Math.max(0.0F,(System.nanoTime()-animationStartedAt)/(float)ANIMATION_DURATION_NANOS));
        float eased=raw*raw*(3.0F-2.0F*raw);
        float progress=animationFrom+(animationTo-animationFrom)*eased;
        if(raw>=1.0F){
            progress=animationTo;
            if(animationTo<=0.0F&&!open){
                visible=false;
                dragging=false;
                resizing=false;
                pointerCaptured=false;
            }
        }
        return progress;
    }

    private static int headerHeight(){
        return HEADER_HEIGHT;
    }

    private static float panelScale(){
        return width/(float)BASE_WIDTH;
    }

    private static float drawScale(){
        return 0.92F+animationProgress()*0.08F;
    }

    private static float drawYOffset(){
        return (1.0F-animationProgress())*5.0F;
    }

    private static int visiblePanelHeight(){
        float scale=panelScale();
        return Math.round((collapsed?COLLAPSED_HEIGHT:BASE_HEIGHT)*scale);
    }

    public static double toPanelX(double screenX){
        float animationScale=drawScale();
        float contentScale=panelScale();
        double centerX=x+width/2.0;
        double unanimatedX=centerX+(screenX-centerX)/animationScale;
        return x+(unanimatedX-x)/contentScale;
    }

    public static double toPanelY(double screenY){
        float animationScale=drawScale();
        float contentScale=panelScale();
        double centerY=y+visiblePanelHeight()/2.0;
        double unanimatedY=centerY+(screenY-centerY-drawYOffset())/animationScale;
        return y+(unanimatedY-y)/contentScale;
    }

    public static void render(DrawContext context,TextRenderer renderer,int screenWidth,int screenHeight){
        float animationScale=drawScale();
        if(!visible||animationProgress()<=0.005F)return;
        ensureBounds(screenWidth,screenHeight);

        float contentScale=panelScale();
        int panelLogicalHeight=collapsed?COLLAPSED_HEIGHT:BASE_HEIGHT;
        int right=x+BASE_WIDTH;
        int bottom=y+panelLogicalHeight;
        float displayWidth=width;
        float displayHeight=visiblePanelHeight();
        float centerX=x+displayWidth/2.0F;
        float centerY=y+displayHeight/2.0F;
        Window pointerWindow=MinecraftClient.getInstance().getWindow();
        double[] rawPointer=getScaledPointerPosition(pointerWindow);
        double panelMouseX=toPanelX(rawPointer[0]);
        double panelMouseY=toPanelY(rawPointer[1]);

        var matrices=context.getMatrices();
        matrices.push();
        // Scale the complete logical panel (text, buttons, counters and log lines)
        // as one surface, then apply the short opening/closing animation.
        matrices.translate(centerX,centerY+drawYOffset(),0.0F);
        matrices.scale(animationScale,animationScale,1.0F);
        matrices.translate(-centerX,-centerY,0.0F);
        matrices.translate(x,y,0.0F);
        matrices.scale(contentScale,contentScale,1.0F);
        matrices.translate(-x,-y,0.0F);

        if(collapsed){
            context.fill(x,y,right,bottom,0xFF77818B);
            context.fill(x+1,y+1,right-1,bottom-1,0xFF303842);
            updateCache(renderer,BASE_WIDTH-16);
            String latest="Ожидание логов...";
            int latestColor=0xFFD0D6DE;
            if(!CACHED_LINES.isEmpty()){
                DisplayLine line=CACHED_LINES.get(CACHED_LINES.size()-1);
                latest=line.text();
                latestColor=line.color();
            }
            context.drawTextWithShadow(renderer,
                    Text.literal(renderer.trimToWidth(latest,BASE_WIDTH-18)),
                    x+8,y+7,latestColor);
            matrices.pop();
            return;
        }

        // One-pixel panel outline; keep the border visually quiet.
        context.fill(x,y,right,bottom,0xFF77818B);
        context.fill(x+1,y+1,right-1,bottom-1,0xFF424B56);
        context.fill(x+2,y+2,right-2,y+HEADER_HEIGHT-1,0xFF343D47);
        context.fill(x+2,y+HEADER_HEIGHT-1,right-2,y+HEADER_HEIGHT,0xFF687482);

        context.drawTextWithShadow(renderer,Text.literal("SERVER LOGS"),x+11,y+9,0xFFFFFFFF);

        boolean copied=System.currentTimeMillis()<copyNoticeUntil;
        String status=copied?copyNotice:AiLogClient.getConnectionStatus();
        int statusColor=copied||AiLogClient.isConnected()?0xFF91DDA5:0xFFFFD27A;
        int statusMax=right-94-(x+112)-5;
        if(statusMax>18){
            context.drawTextWithShadow(renderer,
                    Text.literal(renderer.trimToWidth(status,statusMax)),x+112,y+9,statusColor);
        }

        headerButton(context,renderer,right-94,y+6,39,"COPY",false,panelMouseX,panelMouseY);
        headerButton(context,renderer,right-50,y+6,19,"−",false,panelMouseX,panelMouseY);
        headerButton(context,renderer,right-27,y+6,19,"×",true,panelMouseX,panelMouseY);

        updateCache(renderer,BASE_WIDTH-22);
        int tabY=bottom-22;
        int statsY=tabY-22;
        int contentTop=y+35;
        int contentBottom=statsY-4;
        int lineHeight=11;
        int visibleLines=Math.max(1,(contentBottom-contentTop)/lineHeight);
        int end=Math.max(0,CACHED_LINES.size()-scrollOffset);
        int start=Math.max(0,end-visibleLines);

        // A flat log surface avoids a second thick frame inside the thin outer border.
        context.fill(x+5,contentTop-3,right-5,contentBottom+2,0xFF39424D);
        context.enableScissor(x+8,contentTop-1,right-8,contentBottom+1);
        int lineY=contentTop;
        if(CACHED_LINES.isEmpty()){
            String empty=AiLogClient.getConnectionStatus().isBlank()
                    ?"Ожидание логов...":AiLogClient.getConnectionStatus();
            context.drawTextWithShadow(renderer,
                    Text.literal(renderer.trimToWidth(empty,BASE_WIDTH-28)),
                    x+11,lineY,0xFFD0D6DE);
        }else{
            for(int i=start;i<end;i++){
                DisplayLine line=CACHED_LINES.get(i);
                context.drawTextWithShadow(renderer,Text.literal(line.text()),x+10,lineY,line.color());
                lineY+=lineHeight;
            }
        }
        context.disableScissor();

        drawStats(context,renderer,statsY,BASE_WIDTH);
        drawTabs(context,renderer,tabY,BASE_WIDTH);
        drawScrollbar(context,contentTop,contentBottom,visibleLines,CACHED_LINES.size(),right-7);

        int gripX=right-10,gripY=bottom-10;
        context.fill(gripX+4,gripY+7,gripX+8,gripY+8,0xFFB1BAC5);
        context.fill(gripX+6,gripY+4,gripX+8,gripY+5,0xFFB1BAC5);
        context.fill(gripX+7,gripY+1,gripX+8,gripY+2,0xFFB1BAC5);
        matrices.pop();
    }

    public static boolean mouseClicked(double mouseX,double mouseY,int button,int screenWidth,int screenHeight){
        if(!open||button!=0)return false;
        ensureBounds(screenWidth,screenHeight);

        double hitX=toPanelX(mouseX);
        double hitY=toPanelY(mouseY);
        int panelLogicalHeight=collapsed?COLLAPSED_HEIGHT:BASE_HEIGHT;
        int right=x+BASE_WIDTH;
        int bottom=y+panelLogicalHeight;
        if(hitX<x||hitX>right||hitY<y||hitY>bottom)return false;

        pointerCaptured=true;

        // The collapsed strip contains only the latest log line.
        // Clicking it restores the full panel; no hidden controls are drawn there.
        if(collapsed){
            collapsed=false;
            cachedRevision=-1L;
            AiUiSound.playClick();
            return true;
        }

        if(hitY<=y+HEADER_HEIGHT){
            if(hitX>=right-94&&hitX<right-53){
                copyAllLogs();
                AiUiSound.playClick();
                return true;
            }
            if(hitX>=right-50&&hitX<right-29){
                collapsed=true;
                dragging=false;
                resizing=false;
                pointerCaptured=false;
                AiUiSound.playClick();
                return true;
            }
            if(hitX>=right-27){
                setOpen(false);
                return true;
            }
            dragging=true;
            resizing=false;
            dragMouseX=(int)Math.round(mouseX);
            dragMouseY=(int)Math.round(mouseY);
            dragStartX=x;
            dragStartY=y;
            return true;
        }

        if(hitX>=right-14&&hitY>=bottom-14){
            resizing=true;
            dragging=false;
            dragMouseX=(int)Math.round(mouseX);
            dragMouseY=(int)Math.round(mouseY);
            resizeStartScale=panelScale();
            return true;
        }

        int tabY=bottom-22;
        if(hitY>=tabY&&hitY<=bottom-3){
            int tabWidth=(BASE_WIDTH-24)/3;
            int selected=Math.max(0,Math.min(2,((int)hitX-(x+8))/Math.max(1,tabWidth)));
            if(selected!=filter){
                filter=selected;
                scrollOffset=0;
                cachedRevision=-1L;
                AiUiSound.playClick();
            }
            return true;
        }
        return true;
    }

    public static boolean mouseDragged(double mouseX,double mouseY,int button,int screenWidth,int screenHeight){
        if(!open||button!=0)return false;
        if(dragging){
            x=dragStartX+(int)Math.round(mouseX-dragMouseX);
            y=dragStartY+(int)Math.round(mouseY-dragMouseY);
            ensureBounds(screenWidth,screenHeight);
            return true;
        }
        if(resizing){
            float animationScale=drawScale();
            float horizontalDelta=(float)(mouseX-dragMouseX)/BASE_WIDTH;
            float verticalDelta=(float)(mouseY-dragMouseY)/BASE_HEIGHT;
            float deltaScale=(Math.abs(horizontalDelta)>=Math.abs(verticalDelta)
                    ?horizontalDelta:verticalDelta)/Math.max(0.01F,animationScale);
            float targetScale=resizeStartScale+deltaScale;
            width=Math.round(BASE_WIDTH*targetScale);
            height=Math.round(BASE_HEIGHT*targetScale);
            ensureBounds(screenWidth,screenHeight);
            cachedWidth=-1;
            return true;
        }
        return pointerCaptured;
    }

    public static boolean mouseReleased(double mouseX,double mouseY,int button){
        if(button!=0||!pointerCaptured)return false;
        pointerCaptured=false;
        dragging=false;
        resizing=false;
        return true;
    }

    public static boolean mouseScrolled(double mouseX,double mouseY,double amount,int screenWidth,int screenHeight){
        if(!open||collapsed)return false;
        ensureBounds(screenWidth,screenHeight);
        double hitX=toPanelX(mouseX);
        double hitY=toPanelY(mouseY);
        int contentTop=y+HEADER_HEIGHT+2;
        int contentBottom=y+BASE_HEIGHT-51;
        if(hitX<x||hitX>x+BASE_WIDTH||hitY<contentTop||hitY>contentBottom)return false;

        updateCache(MinecraftClient.getInstance().textRenderer,BASE_WIDTH-22);
        scrollOffset+=amount>0?3:-3;
        int visible=Math.max(1,(contentBottom-contentTop)/11);
        scrollOffset=Math.max(0,Math.min(scrollOffset,Math.max(0,CACHED_LINES.size()-visible)));
        return true;
    }

    private static void copyAllLogs(){
        MinecraftClient client=MinecraftClient.getInstance();
        String logs=AiLogClient.exportAllLogs();
        if(logs==null||logs.isBlank())logs="Логи Minecraft AI Agent пока пусты.";
        client.keyboard.setClipboard(logs);
        copyNotice="Скопировано: "+logs.length()+" симв.";
        copyNoticeUntil=System.currentTimeMillis()+3000L;
    }

    private static void ensureBounds(int screenWidth,int screenHeight){
        float availableWidth=Math.max(1,screenWidth-8)/(float)BASE_WIDTH;
        float availableHeight=Math.max(1,screenHeight-8)/(float)BASE_HEIGHT;
        float maxScale=Math.max(MIN_PANEL_SCALE,
                Math.min(MAX_PANEL_SCALE,Math.min(availableWidth,availableHeight)));
        float scale=Math.max(MIN_PANEL_SCALE,Math.min(panelScale(),maxScale));

        width=Math.round(BASE_WIDTH*scale);
        height=Math.round(BASE_HEIGHT*scale);

        if(x<0)x=Math.max(4,screenWidth-width-18);
        if(y<0)y=Math.max(4,screenHeight-visiblePanelHeight()-60);
        x=Math.max(4,Math.min(x,Math.max(4,screenWidth-width-4)));
        y=Math.max(4,Math.min(y,Math.max(4,screenHeight-visiblePanelHeight()-4)));
    }

    private static void updateCache(TextRenderer renderer,int maxWidth){
        long revision=AiLogClient.getRevision();
        if(cachedRevision==revision&&cachedWidth==maxWidth&&cachedFilter==filter)return;

        int oldLineCount=CACHED_LINES.size();
        boolean wasScrolled=scrollOffset>0;
        CACHED_LINES.clear();
        List<AiLogClient.LogLine> lines=AiLogClient.snapshot(filter);
        for(AiLogClient.LogLine entry:lines){
            String prefix="["+entry.time()+"] ["+entry.level()+"] ["+entry.logger()+"] ";
            String[] messageLines=entry.message().replace('\r','\n').split("\n",-1);
            for(int index=0;index<messageLines.length;index++){
                appendWrapped(renderer,(index==0?prefix:"    ")+messageLines[index],
                        Math.max(24,maxWidth),entry.color());
                if(CACHED_LINES.size()>=10000)break;
            }
            if(CACHED_LINES.size()>=10000)break;
        }

        if(wasScrolled)scrollOffset+=CACHED_LINES.size()-oldLineCount;
        cachedRevision=revision;
        cachedWidth=maxWidth;
        cachedFilter=filter;
        int visible=Math.max(1,(BASE_HEIGHT-86)/11);
        scrollOffset=Math.max(0,Math.min(scrollOffset,Math.max(0,CACHED_LINES.size()-visible)));
    }

    private static void appendWrapped(TextRenderer renderer,String raw,int maxWidth,int color){
        String remaining=raw;
        if(remaining.isEmpty()){
            CACHED_LINES.add(new DisplayLine("",color));
            return;
        }
        while(!remaining.isEmpty()){
            String part=renderer.trimToWidth(remaining,maxWidth);
            if(part.isEmpty())part=remaining.substring(0,1);
            CACHED_LINES.add(new DisplayLine(part,color));
            if(part.length()>=remaining.length())break;
            remaining=remaining.substring(part.length()).stripLeading();
            if(CACHED_LINES.size()>=10000)break;
        }
    }

    private static void drawStats(DrawContext context,TextRenderer renderer,int statsY,int panelWidth){
        int cardWidth=(panelWidth-32)/3;
        drawStat(context,renderer,0,statsY,cardWidth,"TOTAL",AiLogClient.getTotalCount(),0xFF8DDEA4);
        drawStat(context,renderer,1,statsY,cardWidth,"WARN",AiLogClient.getWarningCount(),0xFFFFD477);
        drawStat(context,renderer,2,statsY,cardWidth,"ERROR",AiLogClient.getErrorCount(),0xFFFF7777);
    }

    private static void drawStat(DrawContext context,TextRenderer renderer,int index,int statsY,int cardWidth,
                                 String label,long count,int color){
        int cardX=x+8+index*(cardWidth+8);
        rounded(context,cardX,statsY,cardX+cardWidth,statsY+19,0xFF515C68);
        context.drawTextWithShadow(renderer,Text.literal(label),cardX+6,statsY+6,0xFFD8DEE6);
        String value=formatCount(count);
        int valueWidth=renderer.getWidth(value);
        context.drawTextWithShadow(renderer,Text.literal(value),
                cardX+cardWidth-valueWidth-6,statsY+6,color);
    }

    private static void drawTabs(DrawContext context,TextRenderer renderer,int tabY,int panelWidth){
        String[] labels={"Logs","Custom logs","Errors"};
        int tabWidth=(panelWidth-24)/3;
        for(int i=0;i<labels.length;i++){
            int tabX=x+8+i*tabWidth;
            boolean active=filter==i;
            rounded(context,tabX,tabY,tabX+tabWidth-3,tabY+18,active?0xFF586777:0xFF4A535E);
            if(active)context.fill(tabX+5,tabY+1,tabX+tabWidth-8,tabY+2,0xFFB5D3EF);
            int textWidth=renderer.getWidth(labels[i]);
            context.drawTextWithShadow(renderer,Text.literal(labels[i]),
                    tabX+Math.max(4,(tabWidth-3-textWidth)/2),tabY+5,
                    active?0xFFFFFFFF:0xFFD0D6DE);
        }
    }

    private static void drawScrollbar(DrawContext context,int top,int bottom,int visible,int total,int barX){
        if(total<=visible||bottom<=top)return;
        int track=bottom-top;
        int thumb=Math.max(12,track*visible/Math.max(1,total));
        int offset=Math.max(1,total-visible);
        int thumbY=top+(track-thumb)*scrollOffset/offset;
        context.fill(barX,top,barX+2,bottom,0xFF454E59);
        context.fill(barX,thumbY,barX+2,thumbY+thumb,0xFFB0BDCB);
    }

    private static void headerButton(DrawContext context,TextRenderer renderer,int bx,int by,int bw,
                                     String label,boolean danger,double mouseX,double mouseY){
        boolean hovered=mouseX>=bx&&mouseX<=bx+bw&&mouseY>=by&&mouseY<=by+16;
        rounded(context,bx,by,bx+bw,by+16,hovered?0xFF687583:0xFF4B5561);
        int textWidth=renderer.getWidth(label);
        context.drawTextWithShadow(renderer,Text.literal(label),bx+(bw-textWidth)/2,by+4,
                danger?0xFFFF8989:0xFFEAF0F6);
    }

    private static double[] getScaledPointerPosition(Window window){
        try(MemoryStack stack=MemoryStack.stackPush()){
            DoubleBuffer cursorX=stack.mallocDouble(1);
            DoubleBuffer cursorY=stack.mallocDouble(1);
            GLFW.glfwGetCursorPos(window.getHandle(),cursorX,cursorY);
            return new double[]{
                    cursorX.get(0)*window.getScaledWidth()/(double)window.getWidth(),
                    cursorY.get(0)*window.getScaledHeight()/(double)window.getHeight()
            };
        }
    }

    private static void rounded(DrawContext context,int left,int top,int right,int bottom,int color){
        if(right-left<4||bottom-top<4){
            context.fill(left,top,right,bottom,color);
            return;
        }
        context.fill(left+4,top,right-4,bottom,color);
        context.fill(left+2,top+1,right-2,bottom-1,color);
        context.fill(left+1,top+2,right-1,bottom-2,color);
        context.fill(left,top+4,right,bottom-4,color);
    }

    private static String formatCount(long count){
        if(count>=1_000_000L)return String.format(java.util.Locale.ROOT,"%.1fm",count/1_000_000.0);
        if(count>=10_000L)return String.format(java.util.Locale.ROOT,"%.1fk",count/1_000.0);
        return Long.toString(count);
    }
}
