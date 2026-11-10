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
    private static final int BASE_WIDTH=360, BASE_HEIGHT=210, DIAGNOSTICS_HEIGHT=164, HEADER_HEIGHT=29;
    private static final float MIN_PANEL_SCALE=0.25F, MAX_PANEL_SCALE=1.6F;
    private static boolean open, visible, settingsOpen, diagnosticsEnabled, dragging, resizing, pointerCaptured;
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
            settingsOpen=false;
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

    private static int panelLogicalHeight(){
        return BASE_HEIGHT+(!settingsOpen&&diagnosticsEnabled?DIAGNOSTICS_HEIGHT:0);
    }

    private static int visiblePanelHeight(){
        return Math.round(panelLogicalHeight()*panelScale());
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
        int right=x+BASE_WIDTH;
        int bottom=y+panelLogicalHeight();
        int baseBottom=y+BASE_HEIGHT;
        float displayWidth=width;
        float displayHeight=visiblePanelHeight();
        float centerX=x+displayWidth/2.0F;
        float centerY=y+displayHeight/2.0F;
        MinecraftClient client=MinecraftClient.getInstance();
        Window pointerWindow=client.getWindow();
        double[] rawPointer=getScaledPointerPosition(pointerWindow);
        double panelMouseX=toPanelX(rawPointer[0]);
        double panelMouseY=toPanelY(rawPointer[1]);

        var matrices=context.getMatrices();
        matrices.push();
        // Scale the whole HUD, including optional diagnostics, as one surface.
        matrices.translate(centerX,centerY+drawYOffset(),0.0F);
        matrices.scale(animationScale,animationScale,1.0F);
        matrices.translate(-centerX,-centerY,0.0F);
        matrices.translate(x,y,0.0F);
        matrices.scale(contentScale,contentScale,1.0F);
        matrices.translate(-x,-y,0.0F);

        context.fill(x,y,right,bottom,0xFF77818B);
        context.fill(x+1,y+1,right-1,bottom-1,0xFF424B56);
        context.fill(x+2,y+2,right-2,y+HEADER_HEIGHT-1,0xFF343D47);
        context.fill(x+2,y+HEADER_HEIGHT-1,right-2,y+HEADER_HEIGHT,0xFF687482);

        if(settingsOpen){
            context.drawTextWithShadow(renderer,Text.literal("SETTINGS"),x+11,y+9,0xFFFFFFFF);
            gearButton(context,right-50,y+6,panelMouseX,panelMouseY);
            headerButton(context,renderer,right-27,y+6,19,"×",true,panelMouseX,panelMouseY);
            drawSettings(context,renderer,right);
            drawResizeGrip(context,right,bottom);
            matrices.pop();
            return;
        }

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
        gearButton(context,right-50,y+6,panelMouseX,panelMouseY);
        headerButton(context,renderer,right-27,y+6,19,"×",true,panelMouseX,panelMouseY);

        updateCache(renderer,BASE_WIDTH-22);
        int tabY=baseBottom-22;
        int statsY=tabY-22;
        int contentTop=y+35;
        int contentBottom=statsY-4;
        int lineHeight=11;
        int visibleLines=Math.max(1,(contentBottom-contentTop)/lineHeight);
        int end=Math.max(0,CACHED_LINES.size()-scrollOffset);
        int start=Math.max(0,end-visibleLines);

        context.fill(x+5,contentTop-3,right-5,contentBottom+2,0xFF39424D);
        // Wrapped lines and a calculated line count avoid a fixed scissor rectangle
        // that would not follow the MatrixStack scale on Minecraft 1.20.1.
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

        drawStats(context,renderer,statsY,BASE_WIDTH);
        drawTabs(context,renderer,tabY,BASE_WIDTH);
        drawScrollbar(context,contentTop,contentBottom,visibleLines,CACHED_LINES.size(),right-7);

        if(diagnosticsEnabled){
            drawDiagnostics(context,renderer,baseBottom,client);
        }
        drawResizeGrip(context,right,bottom);
        matrices.pop();
    }

    public static boolean mouseClicked(double mouseX,double mouseY,int button,int screenWidth,int screenHeight){
        if(!open||button!=0)return false;
        ensureBounds(screenWidth,screenHeight);

        double hitX=toPanelX(mouseX);
        double hitY=toPanelY(mouseY);
        int right=x+BASE_WIDTH;
        int bottom=y+panelLogicalHeight();
        if(hitX<x||hitX>right||hitY<y||hitY>bottom)return false;

        pointerCaptured=true;

        if(hitY<=y+HEADER_HEIGHT){
            if(!settingsOpen&&hitX>=right-94&&hitX<right-53){
                copyAllLogs();
                AiUiSound.playClick();
                return true;
            }
            if(hitX>=right-50&&hitX<right-29){
                settingsOpen=!settingsOpen;
                dragging=false;
                resizing=false;
                pointerCaptured=false;
                cachedRevision=-1L;
                ensureBounds(screenWidth,screenHeight);
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

        if(settingsOpen){
            int switchLeft=x+16;
            int switchTop=y+61;
            int switchRight=right-16;
            int switchBottom=switchTop+36;
            if(hitX>=switchLeft&&hitX<=switchRight&&hitY>=switchTop&&hitY<=switchBottom){
                diagnosticsEnabled=!diagnosticsEnabled;
                AiDiagnostics.setEnabled(diagnosticsEnabled);
                ensureBounds(screenWidth,screenHeight);
                AiUiSound.playClick();
                return true;
            }
            return true;
        }

        int tabY=y+BASE_HEIGHT-22;
        if(hitY>=tabY&&hitY<=y+BASE_HEIGHT-3){
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
            float verticalDelta=(float)(mouseY-dragMouseY)/Math.max(1,panelLogicalHeight());
            float deltaScale=(Math.abs(horizontalDelta)>=Math.abs(verticalDelta)
                    ?horizontalDelta:verticalDelta)/Math.max(0.01F,animationScale);
            float targetScale=resizeStartScale+deltaScale;
            width=Math.round(BASE_WIDTH*targetScale);
            height=Math.round(panelLogicalHeight()*targetScale);
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
        if(!open||settingsOpen)return false;
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
        int logicalHeight=panelLogicalHeight();
        float availableWidth=Math.max(1,screenWidth-8)/(float)BASE_WIDTH;
        float availableHeight=Math.max(1,screenHeight-8)/(float)logicalHeight;
        float maxScale=Math.max(MIN_PANEL_SCALE,
                Math.min(MAX_PANEL_SCALE,Math.min(availableWidth,availableHeight)));
        float scale=Math.max(MIN_PANEL_SCALE,Math.min(panelScale(),maxScale));

        width=Math.round(BASE_WIDTH*scale);
        height=Math.round(logicalHeight*scale);

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

    private static void drawSettings(DrawContext context,TextRenderer renderer,int right){
        int cardLeft=x+16;
        int cardTop=y+61;
        int cardRight=right-16;
        int cardBottom=cardTop+36;
        rounded(context,cardLeft,cardTop,cardRight,cardBottom,0xFF505B67);
        context.drawTextWithShadow(renderer,Text.literal("DIAGNOSTICS"),
                cardLeft+10,cardTop+13,0xFFF0F3F7);
        int toggleLeft=cardRight-55;
        int toggleTop=cardTop+8;
        int toggleRight=cardRight-8;
        int toggleBottom=cardBottom-8;
        rounded(context,toggleLeft,toggleTop,toggleRight,toggleBottom,
                diagnosticsEnabled?0xFF427A5B:0xFF626B76);
        String label=diagnosticsEnabled?"ON":"OFF";
        int labelWidth=renderer.getWidth(label);
        context.drawTextWithShadow(renderer,Text.literal(label),
                toggleLeft+(toggleRight-toggleLeft-labelWidth)/2,toggleTop+4,0xFFFFFFFF);
    }

    private static void gearButton(DrawContext context,int bx,int by,double mouseX,double mouseY){
        boolean hovered=mouseX>=bx&&mouseX<=bx+19&&mouseY>=by&&mouseY<=by+16;
        int background=hovered?0xFF687583:0xFF4B5561;
        int icon=0xFFEAF0F6;
        rounded(context,bx,by,bx+19,by+16,background);
        int cx=bx+9,cy=by+8;
        context.fill(cx-1,cy-6,cx+2,cy-3,icon);
        context.fill(cx-1,cy+3,cx+2,cy+6,icon);
        context.fill(cx-6,cy-1,cx-3,cy+2,icon);
        context.fill(cx+3,cy-1,cx+6,cy+2,icon);
        context.fill(cx-4,cy-4,cx-2,cy-2,icon);
        context.fill(cx+2,cy-4,cx+4,cy-2,icon);
        context.fill(cx-4,cy+2,cx-2,cy+4,icon);
        context.fill(cx+2,cy+2,cx+4,cy+4,icon);
        context.fill(cx-3,cy-3,cx+4,cy+4,icon);
        context.fill(cx-1,cy-1,cx+2,cy+2,background);
    }

    private static void drawResizeGrip(DrawContext context,int right,int bottom){
        int gripX=right-10,gripY=bottom-10;
        context.fill(gripX+4,gripY+7,gripX+8,gripY+8,0xFFB1BAC5);
        context.fill(gripX+6,gripY+4,gripX+8,gripY+5,0xFFB1BAC5);
        context.fill(gripX+7,gripY+1,gripX+8,gripY+2,0xFFB1BAC5);
    }

    private static void drawDiagnostics(DrawContext context,TextRenderer renderer,int top,MinecraftClient client){
        int right=x+BASE_WIDTH;
        context.fill(x+5,top,right-5,top+1,0xFF687482);
        AiDiagnostics.Snapshot data=AiDiagnostics.snapshot(client);

        int row1=top+7;
        drawMetric(context,renderer,"PING",data.pingMs()<0?"—":data.pingMs()+" ms",x+10,row1,pingColor(data.pingMs()));
        drawMetric(context,renderer,"TPS",data.serverTps()<0?"—":String.format(java.util.Locale.ROOT,"%.1f",data.serverTps()),x+125,row1,tpsColor(data.serverTps()));
        drawMetric(context,renderer,"TICK",data.serverTickMs()<0?"—":String.format(java.util.Locale.ROOT,"%.0f ms",data.serverTickMs()),x+235,row1,tickColor(data.serverTickMs()));

        int row2=top+21;
        drawMetric(context,renderer,"FPS",data.currentFps()<=0?"—":Integer.toString(data.currentFps()),x+10,row2,fpsColor(data.currentFps()));
        drawMetric(context,renderer,"AVG",data.averageFps()<=0?"—":Integer.toString(data.averageFps()),x+105,row2,fpsColor(data.averageFps()));
        drawMetric(context,renderer,"MAX",data.maxFps()<=0?"—":Integer.toString(data.maxFps()),x+200,row2,fpsColor(data.maxFps()));
        context.drawTextWithShadow(renderer,Text.literal("AVG ON"),x+285,row2,0xFF91DDA5);

        int row3=top+35;
        int resolutionWidth=data.resolutionWidth(),resolutionHeight=data.resolutionHeight();
        drawMetric(context,renderer,"RES",resolutionWidth>0&&resolutionHeight>0?resolutionWidth+"x"+resolutionHeight:"—",x+10,row3,0xFFE0E6ED);
        drawMetric(context,renderer,"GPU",data.gpuLoad()<0?"—":Math.round(data.gpuLoad())+"%",x+112,row3,percentColor(data.gpuLoad()));
        drawMetric(context,renderer,"CPU",data.cpuLoad()<0?"—":Math.round(data.cpuLoad())+"%",x+190,row3,percentColor(data.cpuLoad()));
        String ram=data.totalMemory()<=0?"—":String.format(java.util.Locale.ROOT,"%.1f/%.1fG",data.usedMemory()/1073741824.0,data.totalMemory()/1073741824.0);
        float ramPercent=data.totalMemory()<=0?-1.0F:data.usedMemory()*100.0F/data.totalMemory();
        drawMetric(context,renderer,"RAM",ram,x+265,row3,percentColor(ramPercent));

        int row4=top+49;
        if(client.player!=null){
            String coordinates=String.format(java.util.Locale.ROOT,"%.1f  %.1f  %.1f",client.player.getX(),client.player.getY(),client.player.getZ());
            context.drawTextWithShadow(renderer,Text.literal("XYZ"),x+10,row4,0xFFB9C4D0);
            context.drawTextWithShadow(renderer,Text.literal(coordinates),x+34,row4,0xFFE4E9EF);
        }
        if(client.world!=null){
            String dimension=client.world.getRegistryKey().getValue().getPath().toUpperCase(java.util.Locale.ROOT);
            context.drawTextWithShadow(renderer,Text.literal("DIM"),x+265,row4,0xFFB9C4D0);
            context.drawTextWithShadow(renderer,Text.literal(renderer.trimToWidth(dimension,BASE_WIDTH-305)),x+289,row4,0xFFD0D8E1);
        }

        int chartWidth=(BASE_WIDTH-22)/2;
        int firstChartTop=top+64,secondChartTop=top+110;
        drawHistoryChart(context,renderer,x+8,firstChartTop,chartWidth,42,"FPS",data.fpsHistory(),"FPS");
        drawHistoryChart(context,renderer,x+14+chartWidth,firstChartTop,chartWidth,42,"TPS",data.tpsHistory(),"TPS");
        drawHistoryChart(context,renderer,x+8,secondChartTop,chartWidth,42,"GPU",data.gpuHistory(),"PERCENT");
        drawHistoryChart(context,renderer,x+14+chartWidth,secondChartTop,chartWidth,42,"CPU",data.cpuHistory(),"PERCENT");
    }

    private static void drawMetric(DrawContext context,TextRenderer renderer,String label,String value,
                                   int left,int y,int valueColor){
        context.drawTextWithShadow(renderer,Text.literal(label),left,y,0xFFB9C4D0);
        int valueX=left+renderer.getWidth(label)+3;
        context.drawTextWithShadow(renderer,Text.literal(value),valueX,y,valueColor);
    }

    private static void drawHistoryChart(DrawContext context,TextRenderer renderer,int left,int top,
                                         int width,int height,String title,float[] values,String metric){
        rounded(context,left,top,left+width,top+height,0xFF37414C);
        context.drawTextWithShadow(renderer,Text.literal(title),left+6,top+4,0xFFD5DDE6);
        int graphTop=top+16,graphBottom=top+height-4;
        int graphHeight=Math.max(1,graphBottom-graphTop),graphWidth=Math.max(1,width-12);
        int count=values.length,barWidth=Math.max(1,graphWidth/Math.max(1,count));
        float maximum=switch(metric){case "FPS"->60.0F;case "TPS"->20.0F;default->100.0F;};
        for(float value:values)if(value>maximum)maximum=value;
        maximum=Math.max(1.0F,maximum*1.12F);
        for(int i=0;i<count;i++){
            float value=values[i];
            if(value<0.0F||!Float.isFinite(value))continue;
            int barHeight=value<=0.0F?0:Math.max(1,Math.round(value/maximum*graphHeight));
            if(barHeight<=0)continue;
            int barX=left+6+i*barWidth;
            if(barX>=left+width-4)break;
            int color=switch(metric){case "FPS"->fpsColor(value);case "TPS"->tpsColor(value);default->percentColor(value);};
            context.fill(barX,graphBottom-barHeight,Math.min(barX+barWidth-1,left+width-5),graphBottom,color);
        }
    }

    private static int fpsColor(float fps){
        if(fps<0.0F)return 0xFFD0D6DE;
        if(fps<30.0F)return 0xFFFF7777;
        if(fps<60.0F)return 0xFFFFCD70;
        return 0xFF83D99B;
    }

    private static int pingColor(int ping){
        if(ping<0)return 0xFFD0D6DE;
        if(ping<=80)return 0xFF83D99B;
        if(ping<=160)return 0xFFFFCD70;
        return 0xFFFF7777;
    }

    private static int tpsColor(float tps){
        if(tps<0.0F)return 0xFFD0D6DE;
        if(tps>=19.0F)return 0xFF83D99B;
        if(tps>=15.0F)return 0xFFFFCD70;
        return 0xFFFF7777;
    }

    private static int tickColor(float milliseconds){
        if(milliseconds<0.0F)return 0xFFD0D6DE;
        if(milliseconds<=50.0F)return 0xFF83D99B;
        if(milliseconds<=65.0F)return 0xFFFFCD70;
        return 0xFFFF7777;
    }

    private static int percentColor(float percent){
        if(percent<0.0F)return 0xFFD0D6DE;
        if(percent<=60.0F)return 0xFF83D99B;
        if(percent<=85.0F)return 0xFFFFCD70;
        return 0xFFFF7777;
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
