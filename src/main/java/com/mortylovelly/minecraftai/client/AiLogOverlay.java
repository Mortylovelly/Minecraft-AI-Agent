package com.mortylovelly.minecraftai.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.Window;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

public final class AiLogOverlay {
    private static boolean open, visible, collapsed, dragging, resizing, pointerCaptured;
    private static int x=-1, y=-1, width=120, height=70;
    private static int dragMouseX, dragMouseY, dragStartX, dragStartY, resizeStartWidth, resizeStartHeight;
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

    private static boolean compact(){
        return width<240||height<140;
    }

    private static int headerHeight(){
        return compact()?24:29;
    }

    private static float drawScale(){
        return 0.92F+animationProgress()*0.08F;
    }

    private static float drawYOffset(){
        return (1.0F-animationProgress())*5.0F;
    }

    public static double toPanelX(double screenX){
        float scale=drawScale();
        double center=x+width/2.0;
        return center+(screenX-center)/scale;
    }

    public static double toPanelY(double screenY){
        float scale=drawScale();
        int panelHeight=collapsed?headerHeight():height;
        double center=y+panelHeight/2.0;
        return center+(screenY-center-drawYOffset())/scale;
    }

    public static void render(DrawContext context,TextRenderer renderer,int screenWidth,int screenHeight){
        float progress=animationProgress();
        if(!visible||progress<=0.005F)return;
        ensureBounds(screenWidth,screenHeight);

        boolean compact=compact();
        int panelHeaderHeight=headerHeight();
        int panelHeight=collapsed?panelHeaderHeight:height;
        int right=x+width;
        int bottom=y+panelHeight;
        float scale=0.92F+progress*0.08F;

        var matrices=context.getMatrices();
        matrices.push();
        matrices.translate(x+width/2.0F,y+panelHeight/2.0F+(1.0F-progress)*5.0F,0.0F);
        matrices.scale(scale,scale,1.0F);
        matrices.translate(-(x+width/2.0F),-(y+panelHeight/2.0F),0.0F);

        rounded(context,x,y,right,bottom,0xFF74808D);
        rounded(context,x+1,y+1,right-1,bottom-1,0xFF424B56);
        rounded(context,x+3,y+2,right-3,y+panelHeaderHeight-1,0xFF343D47);
        context.fill(x+5,y+panelHeaderHeight-1,right-5,y+panelHeaderHeight,0xFF687482);

        if(compact){
            context.drawTextWithShadow(renderer,Text.literal("LOGS"),x+8,y+8,0xFFFFFFFF);
            int copyX=right-57,collapseX=right-38,closeX=right-19;
            headerButton(context,renderer,copyX,y+4,16,"C",false);
            headerButton(context,renderer,collapseX,y+4,16,collapsed?"+":"−",false);
            headerButton(context,renderer,closeX,y+4,16,"×",true);
        }else{
            context.drawTextWithShadow(renderer,Text.literal("SERVER LOGS"),x+11,y+9,0xFFFFFFFF);

            boolean copied=System.currentTimeMillis()<copyNoticeUntil;
            String status=copied?copyNotice:AiLogClient.getConnectionStatus();
            int statusColor=copied||AiLogClient.isConnected()?0xFF91DDA5:0xFFFFD27A;
            int statusMax=right-94-(x+112)-5;
            if(statusMax>18){
                context.drawTextWithShadow(renderer,
                        Text.literal(renderer.trimToWidth(status,statusMax)),x+112,y+9,statusColor);
            }

            headerButton(context,renderer,right-94,y+6,39,"COPY",false);
            headerButton(context,renderer,right-50,y+6,19,collapsed?"+":"−",false);
            headerButton(context,renderer,right-27,y+6,19,"×",true);
        }

        if(!collapsed){
            updateCache(renderer,compact?width-16:width-22);
            if(compact){
                int contentTop=y+panelHeaderHeight+2;
                int contentBottom=bottom-5;
                int lineHeight=9;
                int visibleLines=Math.max(1,(contentBottom-contentTop)/lineHeight);
                int end=Math.max(0,CACHED_LINES.size()-scrollOffset);
                int start=Math.max(0,end-visibleLines);
                rounded(context,x+4,contentTop-1,right-4,contentBottom+1,0xFF39424D);
                context.enableScissor(x+5,contentTop,right-5,contentBottom);
                int lineY=contentTop+1;
                if(CACHED_LINES.isEmpty()){
                    String empty="Ожидание логов...";
                    context.drawTextWithShadow(renderer,Text.literal(renderer.trimToWidth(empty,width-16)),
                            x+7,lineY,0xFFD0D6DE);
                }else{
                    for(int i=start;i<end;i++){
                        DisplayLine line=CACHED_LINES.get(i);
                        context.drawTextWithShadow(renderer,
                                Text.literal(renderer.trimToWidth(line.text(),width-16)),
                                x+7,lineY,line.color());
                        lineY+=lineHeight;
                    }
                }
                context.disableScissor();
            }else{
                int tabY=bottom-22;
                int statsY=tabY-22;
                int contentTop=y+35;
                int contentBottom=statsY-4;
                int lineHeight=11;
                int visibleLines=Math.max(1,(contentBottom-contentTop)/lineHeight);
                int end=Math.max(0,CACHED_LINES.size()-scrollOffset);
                int start=Math.max(0,end-visibleLines);
                rounded(context,x+5,contentTop-3,right-5,contentBottom+2,0xFF39424D);
                context.enableScissor(x+8,contentTop-1,right-8,contentBottom+1);
                int lineY=contentTop;
                if(CACHED_LINES.isEmpty()){
                    String empty=AiLogClient.getConnectionStatus().isBlank()
                            ?"Ожидание логов...":AiLogClient.getConnectionStatus();
                    context.drawTextWithShadow(renderer,Text.literal(renderer.trimToWidth(empty,width-28)),
                            x+11,lineY,0xFFD0D6DE);
                }else{
                    for(int i=start;i<end;i++){
                        DisplayLine line=CACHED_LINES.get(i);
                        context.drawTextWithShadow(renderer,Text.literal(line.text()),x+10,lineY,line.color());
                        lineY+=lineHeight;
                    }
                }
                context.disableScissor();
                drawStats(context,renderer,statsY,width);
                drawTabs(context,renderer,tabY,width);
                drawScrollbar(context,contentTop,contentBottom,visibleLines,CACHED_LINES.size(),right-7);
            }

            int gripX=right-10,gripY=bottom-10;
            context.fill(gripX+4,gripY+7,gripX+8,gripY+8,0xFFB1BAC5);
            context.fill(gripX+6,gripY+4,gripX+8,gripY+5,0xFFB1BAC5);
            context.fill(gripX+7,gripY+1,gripX+8,gripY+2,0xFFB1BAC5);
        }
        matrices.pop();
    }

    public static boolean mouseClicked(double mouseX,double mouseY,int button,int screenWidth,int screenHeight){
        if(!open||button!=0)return false;
        ensureBounds(screenWidth,screenHeight);

        boolean compact=compact();
        int panelHeaderHeight=headerHeight();
        int panelHeight=collapsed?panelHeaderHeight:height;
        int right=x+width;
        int bottom=y+panelHeight;
        if(mouseX<x||mouseX>right||mouseY<y||mouseY>bottom)return false;

        pointerCaptured=true;

        if(compact){
            if(mouseY<=y+panelHeaderHeight){
                if(mouseX>=right-57&&mouseX<right-40){
                    copyAllLogs();
                    AiUiSound.playClick();
                    return true;
                }
                if(mouseX>=right-38&&mouseX<right-21){
                    collapsed=!collapsed;
                    dragging=false;
                    resizing=false;
                    AiUiSound.playClick();
                    return true;
                }
                if(mouseX>=right-19){
                    setOpen(false);
                    return true;
                }
                dragging=true;
                resizing=false;
                dragMouseX=(int)mouseX;
                dragMouseY=(int)mouseY;
                dragStartX=x;
                dragStartY=y;
                return true;
            }
        }else if(mouseY<=y+panelHeaderHeight){
            if(mouseX>=right-94&&mouseX<right-53){
                copyAllLogs();
                AiUiSound.playClick();
                return true;
            }
            if(mouseX>=right-50&&mouseX<right-29){
                collapsed=!collapsed;
                dragging=false;
                resizing=false;
                AiUiSound.playClick();
                return true;
            }
            if(mouseX>=right-27){
                setOpen(false);
                return true;
            }
            dragging=true;
            resizing=false;
            dragMouseX=(int)mouseX;
            dragMouseY=(int)mouseY;
            dragStartX=x;
            dragStartY=y;
            return true;
        }

        if(collapsed)return true;

        if(mouseX>=right-14&&mouseY>=bottom-14){
            resizing=true;
            dragging=false;
            dragMouseX=(int)mouseX;
            dragMouseY=(int)mouseY;
            resizeStartWidth=width;
            resizeStartHeight=height;
            return true;
        }

        if(!compact){
            int tabY=bottom-22;
            if(mouseY>=tabY&&mouseY<=bottom-3){
                int tabWidth=(width-24)/3;
                int selected=Math.max(0,Math.min(2,((int)mouseX-(x+8))/Math.max(1,tabWidth)));
                if(selected!=filter){
                    filter=selected;
                    scrollOffset=0;
                    cachedRevision=-1L;
                    AiUiSound.playClick();
                }
                return true;
            }
        }
        return true;
    }

    public static boolean mouseDragged(double mouseX,double mouseY,int button,int screenWidth,int screenHeight){
        if(!open||button!=0)return false;
        if(dragging){
            x=dragStartX+(int)mouseX-dragMouseX;
            y=dragStartY+(int)mouseY-dragMouseY;
            ensureBounds(screenWidth,screenHeight);
            return true;
        }
        if(resizing){
            width=resizeStartWidth+(int)mouseX-dragMouseX;
            height=resizeStartHeight+(int)mouseY-dragMouseY;
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
        boolean compact=compact();
        int contentTop=y+headerHeight()+2;
        int contentBottom=compact?y+height-5:y+height-51;
        if(mouseX<x||mouseX>x+width||mouseY<contentTop||mouseY>contentBottom)return false;

        updateCache(MinecraftClient.getInstance().textRenderer,compact?width-16:width-22);
        scrollOffset+=amount>0?3:-3;
        int visible=Math.max(1,(contentBottom-contentTop)/(compact?9:11));
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
        int maxWidth=Math.max(120,screenWidth/2);
        int maxHeight=Math.max(70,screenHeight/2);
        int minWidth=Math.min(120,maxWidth);
        int minHeight=Math.min(70,maxHeight);
        width=Math.max(minWidth,Math.min(width,maxWidth));
        height=Math.max(minHeight,Math.min(height,maxHeight));

        if(x<0)x=Math.max(4,screenWidth-width-18);
        if(y<0)y=Math.max(4,screenHeight-height-60);
        x=Math.max(4,Math.min(x,Math.max(4,screenWidth-width-4)));
        y=Math.max(4,Math.min(y,Math.max(4,screenHeight-(collapsed?headerHeight():height)-4)));
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
        boolean compact=compact();
        int visible=compact?Math.max(1,(height-headerHeight()-7)/9):Math.max(1,(height-86)/11);
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
        drawStat(context,renderer,0,statsY,cardWidth,Items.EMERALD,AiLogClient.getTotalCount(),0xFF8DDEA4);
        drawStat(context,renderer,1,statsY,cardWidth,Items.GLOWSTONE_DUST,AiLogClient.getWarningCount(),0xFFFFD477);
        drawStat(context,renderer,2,statsY,cardWidth,Items.REDSTONE,AiLogClient.getErrorCount(),0xFFFF7777);
    }

    private static void drawStat(DrawContext context,TextRenderer renderer,int index,int statsY,int cardWidth,
                                 net.minecraft.item.Item item,long count,int color){
        int cardX=x+8+index*(cardWidth+8);
        rounded(context,cardX,statsY,cardX+cardWidth,statsY+19,0xFF515C68);
        context.drawItem(new ItemStack(item),cardX+3,statsY+2);
        context.drawTextWithShadow(renderer,Text.literal(formatCount(count)),cardX+23,statsY+6,color);
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
                                     String label,boolean danger){
        MinecraftClient client=MinecraftClient.getInstance();
        Window window=client.getWindow();
        double mouseX=client.mouse.getX()*window.getScaledWidth()/(double)window.getWidth();
        double mouseY=client.mouse.getY()*window.getScaledHeight()/(double)window.getHeight();
        mouseX=toPanelX(mouseX);
        mouseY=toPanelY(mouseY);
        boolean hovered=mouseX>=bx&&mouseX<=bx+bw&&mouseY>=by&&mouseY<=by+16;
        rounded(context,bx,by,bx+bw,by+16,hovered?0xFF687583:0xFF4B5561);
        int textWidth=renderer.getWidth(label);
        context.drawTextWithShadow(renderer,Text.literal(label),bx+(bw-textWidth)/2,by+4,
                danger?0xFFFF8989:0xFFEAF0F6);
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
