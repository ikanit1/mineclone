package com.mineclone.world.shape;

/** Slabs, dynamic fence arms and hinged panels; persisted meta stores only placement state. */
final class BuildingShapes {
    private BuildingShapes() {}
    static final BlockShape SLAB = new BlockShape() {
        public int collision(byte m,float[] b){ return boxes(m,b); }
        public int outline(byte m,float[] b){ return boxes(m,b); }
        public boolean fullCube(byte m){ return (m&2)!=0; }
        private int boxes(byte m,float[] b){
            float low=(m&2)!=0?0:(m&1)*.5f, high=(m&2)!=0?1:low+.5f;
            return box(b,0,0,low,0,1,high,1);
        }
    };
    static final BlockShape FENCE = new BlockShape() {
        public float maxCollisionHeight(){return 1.5f;}
        public int collision(byte m,float[] b){ return arms(m,b,1.5f,false); }
        public int outline(byte m,float[] b){ return arms(m,b,1f,false); }
        public int mesh(byte m,float[] b){ return arms(m,b,1f,true); }
        private int arms(byte m,float[] b,float h,boolean rails){
            int n=box(b,0,.375f,0,.375f,.625f,h,.625f);
            for(int d=0;d<4;d++) if((m&(1<<d))!=0){
                float x0=d==3?0:.4375f,x1=d==2?1:.5625f;
                float z0=d==1?0:.4375f,z1=d==0?1:.5625f;
                if(rails){ n=box(b,n,x0,.375f,z0,x1,.5625f,z1); n=box(b,n,x0,.75f,z0,x1,.9375f,z1); }
                else n=box(b,n,x0,0,z0,x1,h,z1);
            }
            return n;
        }
    };
    static final BlockShape GATE = new BlockShape() {
        public float maxCollisionHeight(){return 1.5f;}
        public int collision(byte m,float[] b){ return (m&4)!=0?0:panel(b,(m&1)!=0,.375f,.625f,0,1.5f); }
        public int outline(byte m,float[] b){
            if((m&4)==0)return panel(b,(m&1)!=0,.375f,.625f,0,1);
            return panel(b,(m&1)==0,0,Shapes.DOOR_THICKNESS,0,1);
        }
    };
    static final BlockShape LADDER = new BlockShape() {
        public int collision(byte m,float[] b){return 0;}
        public int outline(byte m,float[] b){
            return switch(m&7){
                case 1 -> box(b,0,0,0,0,1f/16,1,1);
                case 2 -> box(b,0,15f/16,0,0,1,1,1);
                case 3 -> box(b,0,0,0,0,1,1,1f/16);
                default -> box(b,0,0,0,15f/16,1,1,1);
            };
        }
    };
    static final BlockShape TRAPDOOR = new BlockShape() {
        public int collision(byte m,float[] b){return outline(m,b);}
        public int outline(byte m,float[] b){
            float th=Shapes.DOOR_THICKNESS;
            if((m&4)==0){float low=(m&8)!=0?1-th:0;return box(b,0,0,low,0,1,low+th,1);}
            return switch(m&3){
                case 0 -> box(b,0,0,0,1-th,1,1,1);
                case 1 -> box(b,0,0,0,0,th,1,1);
                case 2 -> box(b,0,0,0,0,1,1,th);
                default -> box(b,0,1-th,0,0,1,1,1);
            };
        }
    };
    private static int panel(float[] b,boolean x,float low,float high,float y0,float y1){
        return box(b,0,x?low:0,y0,x?0:low,x?high:1,y1,x?1:high);
    }
    private static int box(float[] b,int n,float x0,float y0,float z0,float x1,float y1,float z1){
        return BlockShape.box(b,n,x0,y0,z0,x1,y1,z1);
    }
}
