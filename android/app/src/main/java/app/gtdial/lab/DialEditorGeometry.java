package app.gtdial.lab;

/** Editor-only grouping: serialized projects retain the two proven runtime nodes. */
final class DialEditorGeometry {
    static final int TIME_GAP=8;
    private static final int SNAP_DISTANCE=6;
    static DialProject.Element find(DialProject project,String id){
        for(DialProject.Element e:project.elements)if(e.id.equals(id))return e;return null;
    }
    static boolean joinedTime(DialProject project){
        DialProject.Element hour=find(project,"HOUR"),minute=find(project,"MIN");
        return hour!=null&&minute!=null&&minute.x==hour.x+hour.width+TIME_GAP
            &&minute.y==hour.y&&minute.argb==hour.argb;
    }
    static boolean timeNode(String id){return "HOUR".equals(id)||"MIN".equals(id);}
    static DialProject.Element bounds(DialProject project,String id){
        DialProject.Element e=find(project,id);
        if(timeNode(id)&&joinedTime(project)){
            DialProject.Element hour=find(project,"HOUR"),minute=find(project,"MIN");
            return new DialProject.Element("HOUR",hour.x,hour.y,minute.x+minute.width-hour.x,hour.height,hour.argb);
        }
        return e;
    }
    static boolean valid(DialProject.Element e){
        return e.x>=0&&e.y>=0&&e.x+e.width<=466&&e.y+e.height<=466&&e.inCircle();
    }
    static int[] snap(DialProject project,String id,int x,int y){
        DialProject.Element node=find(project,id),box=bounds(project,id);
        int offsetX=node.x-box.x,offsetY=node.y-box.y;
        int left=x-offsetX,top=y-offsetY,sx=left,sy=top;
        int bestX=SNAP_DISTANCE+1,bestY=SNAP_DISTANCE+1;
        for(DialProject.Element other:project.elements){
            if(other.id.equals(id)||(timeNode(id)&&joinedTime(project)&&timeNode(other.id)))continue;
            // Same edges/centres and adjacent edges; the nearest anchor wins per axis.
            int[] xs={other.x,other.x+other.width-box.width,Math.round(other.x+(other.width-box.width)/2f),other.x+other.width,other.x-box.width};
            int[] ys={other.y,other.y+other.height-box.height,Math.round(other.y+(other.height-box.height)/2f),other.y+other.height,other.y-box.height};
            for(int target:xs){int distance=Math.abs(target-left);if(distance<bestX){bestX=distance;sx=target;}}
            for(int target:ys){int distance=Math.abs(target-top);if(distance<bestY){bestY=distance;sy=target;}}
        }
        if(!valid(box.at(sx,sy))){
            if(valid(box.at(sx,top)))sy=top;
            else if(valid(box.at(left,sy)))sx=left;
            else{sx=left;sy=top;}
        }
        return new int[]{sx+offsetX,sy+offsetY};
    }
    private DialEditorGeometry(){}
}
