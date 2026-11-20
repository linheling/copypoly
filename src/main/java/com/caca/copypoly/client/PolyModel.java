package com.caca.copypoly.client;

import com.caca.copypoly.*;
import com.caca.copypoly.geometry.Polyhedron;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.decoration.copycat.CopycatModel;
import com.simibubi.create.content.decoration.copycat.FilteredBlockAndTintGetter;
import com.simibubi.create.foundation.block.connected.CTModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.block.model.*;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.*;
import net.neoforged.neoforge.common.util.TriState;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Builds immutable mesh snapshots on chunk rebuild; getQuads itself only performs lookups. */
public class PolyModel extends BakedModelWrapper<BakedModel> {
    private record Mesh(Map<RenderType,List<BakedQuad>> layers, TextureAtlasSprite particle) {}
    private record Key(Polyhedron poly, Direction direction, List<BakedQuad> source) {}
    private static final ModelProperty<Mesh> MESH = new ModelProperty<>();
    private final Map<Key,List<BakedQuad>> cache = Collections.synchronizedMap(new LinkedHashMap<>(512,.75f,true) {
        protected boolean removeEldestEntry(Map.Entry<Key,List<BakedQuad>> e) { return size()>512; }
    });
    private final Map<Polyhedron,PolyModel> itemCache = new LinkedHashMap<>(64,.75f,true) {
        protected boolean removeEldestEntry(Map.Entry<Polyhedron,PolyModel> e) {return size()>64;}
    };
    private final Polyhedron itemShape;
    private Mesh itemMesh;
    public PolyModel(BakedModel base) { this(base,Polyhedron.CUBE); }
    private PolyModel(BakedModel base,Polyhedron shape) { super(base);itemShape=shape; }
    public static Direction direction(Polyhedron.Plane p) {
        return switch(Polyhedron.dominant(p)) {
            case 0 -> p.x()>0?Direction.EAST:Direction.WEST;
            case 1 -> p.y()>0?Direction.UP:Direction.DOWN;
            default -> p.z()>0?Direction.SOUTH:Direction.NORTH;
        };
    }
    private static final Map<Class<?>,Boolean> CONNECTS = new ConcurrentHashMap<>();
    /**
     * Whether this material model reacts to its neighbours on its own: Create's {@code CTModel} (covers
     * connected glass, casings, pillars) plus the third-party connected-texture wrappers that author packs
     * may stack on top (Fusion's {@code ConnectingBakedModel}, Continuity's {@code CtmBakedModel}).
     * Anything else ignores the world, so there is nothing to gain from showing it its neighbours.
     */
    private static boolean connects(BakedModel model) {
        return CONNECTS.computeIfAbsent(model.getClass(),type -> {
            if(model instanceof CTModel) return true;
            String name=type.getName();
            return name.endsWith("CTModel") || name.contains("CtmBakedModel") || name.contains("ConnectingBakedModel");
        });
    }
    @Override public ModelData getModelData(BlockAndTintGetter world,BlockPos pos,BlockState state,ModelData data) {
        BlockState material=CopycatModel.getMaterial(data);
        Polyhedron poly=data.get(PolyBlockEntity.GEOMETRY);
        if(poly==null) poly=Polyhedron.CUBE;
        BakedModel source=Minecraft.getInstance().getBlockRenderer().getBlockModel(material);
        // Two routes in one:
        //  * material model connects (Create CTModel / Fusion / Continuity) -> hand it a view that only
        //    exposes the sides this hull actually shows a face towards, so the connected tile it picks
        //    matches the geometry. The UV shift it bakes in is then resampled by position in projectFace.
        //  * material model is plain -> seal the view to this block, which is the LittleTiles route: no
        //    neighbour queries, guaranteed plain tiles, texture continuity carried purely by position.
        ModelData sourceData;
        if(connects(source)) {
            BlockState self=state;
            FilteredBlockAndTintGetter view=new FilteredBlockAndTintGetter(world,
                p -> p.equals(pos) || (self.getBlock() instanceof PolyBlock block && block.canConnectTexturesToward(world,pos,p,self)));
            sourceData=source.getModelData(view,pos,material,ModelData.EMPTY);
        } else {
            FilteredBlockAndTintGetter sealed=new FilteredBlockAndTintGetter(world,pos::equals);
            sourceData=source.getModelData(sealed,pos,material,ModelData.EMPTY);
        }
        return data.derive().with(MESH,mesh(poly,material,source,sourceData,material.getSeed(pos))).build();
    }
    private Mesh mesh(Polyhedron poly,BlockState material,BakedModel source,ModelData data,long seed) {
        Map<RenderType,List<BakedQuad>> layers=new LinkedHashMap<>();
        RandomSource random=RandomSource.create(seed);
        for(RenderType type:source.getRenderTypes(material,random,data)) {
            List<BakedQuad> result=new ArrayList<>();
            for(Direction dir:Direction.values()) {
                random.setSeed(seed);
                List<BakedQuad> quads=new ArrayList<>(source.getQuads(material,dir,random,data,type));
                random.setSeed(seed);
                for(BakedQuad q:source.getQuads(material,null,random,data,type)) if(q.getDirection()==dir) quads.add(q);
                if(quads.isEmpty()) continue;
                Key key=new Key(poly,dir,List.copyOf(quads));
                List<BakedQuad> projected=cache.get(key);
                if(projected==null) {
                    List<BakedQuad> built=new ArrayList<>();
                    for(Polyhedron.Face face:poly.faces()) if(direction(face.plane())==dir)
                        for(BakedQuad q:quads) projectFace(built,face,q,dir);
                    projected=List.copyOf(built); cache.put(key,projected);
                }
                result.addAll(projected);
            }
            layers.put(type,List.copyOf(result));
        }
        return new Mesh(Collections.unmodifiableMap(layers),source.getParticleIcon(data));
    }
    private Mesh itemMesh() {
        if(itemMesh==null) {
            BlockState mat=AllBlocks.COPYCAT_BASE.getDefaultState();
            itemMesh=mesh(itemShape,mat,Minecraft.getInstance().getBlockRenderer().getBlockModel(mat),ModelData.EMPTY,42);
        }
        return itemMesh;
    }
    public List<BakedQuad> preview(Polyhedron poly, BlockState material) {
        Mesh m=mesh(poly,material,Minecraft.getInstance().getBlockRenderer().getBlockModel(material),ModelData.EMPTY,42);
        return m.layers.values().stream().flatMap(Collection::stream).toList();
    }
    @Override public List<BakedQuad> getQuads(BlockState state,Direction side,RandomSource rand) {
        return getQuads(state,side,rand,ModelData.EMPTY,null);
    }
    @Override public List<BakedQuad> getQuads(BlockState state,Direction side,RandomSource rand,ModelData data,RenderType type) {
        if(side!=null) return List.of(); // Slanted faces must not be culled using the full-block neighbor test.
        Mesh m=data.get(MESH); if(m==null) m=itemMesh();
        if(type!=null) return m.layers.getOrDefault(type,List.of());
        return m.layers.values().stream().flatMap(Collection::stream).toList();
    }
    @Override public ChunkRenderTypeSet getRenderTypes(BlockState s,RandomSource r,ModelData d) {
        Mesh m=d.get(MESH);
        return m==null?ChunkRenderTypeSet.of(RenderType.solid()):ChunkRenderTypeSet.of(m.layers.keySet());
    }
    @Override public TextureAtlasSprite getParticleIcon(ModelData data) {
        Mesh m=data.get(MESH);return m==null?super.getParticleIcon(data):m.particle;
    }
    @Override public boolean useAmbientOcclusion() {return false;}
    @Override public TriState useAmbientOcclusion(BlockState s,ModelData d,RenderType t) {return TriState.FALSE;}
    @Override public List<BakedModel> getRenderPasses(ItemStack stack,boolean fabulous) {return List.of(this);}
    @Override public List<RenderType> getRenderTypes(ItemStack stack,boolean fabulous) {return List.of(RenderType.entitySolid(TextureAtlas.LOCATION_BLOCKS));}
    @Override public BakedModel applyTransform(ItemDisplayContext type,PoseStack poses,boolean left) {
        originalModel.applyTransform(type,poses,left);return this;
    }
    @Override public ItemOverrides getOverrides() {
        return new ItemOverrides() {
            @Override public BakedModel resolve(BakedModel model,ItemStack stack,ClientLevel level,LivingEntity entity,int seed) {
                Polyhedron shape=PolyItem.readShape(stack);
                if(shape==itemShape) return PolyModel.this;
                return itemCache.computeIfAbsent(shape,s->new PolyModel(originalModel,s));
            }
        };
    }
    private static double a(Vec3 p,Direction dir) {return dir.getAxis()==Direction.Axis.X?p.z:p.x;}
    private static double b(Vec3 p,Direction dir) {return dir.getAxis()==Direction.Axis.Y?p.z:p.y;}
    private static Vec3 vertex(int[] data,int stride,int i) {
        return new Vec3(Float.intBitsToFloat(data[i*stride]),Float.intBitsToFloat(data[i*stride+1]),Float.intBitsToFloat(data[i*stride+2]));
    }
    private static List<Vec3> clip(List<Vec3> poly,Direction dir,boolean axis,double boundary,boolean greater) {
        List<Vec3> out=new ArrayList<>(); if(poly.isEmpty())return out;
        Vec3 prev=poly.getLast(); double pd=(axis?a(prev,dir):b(prev,dir))-boundary;
        for(Vec3 next:poly) {
            double nd=(axis?a(next,dir):b(next,dir))-boundary;
            boolean pin=greater?pd>=-1e-7:pd<=1e-7, nin=greater?nd>=-1e-7:nd<=1e-7;
            if(pin!=nin) out.add(prev.lerp(next,pd/(pd-nd)));
            if(nin)out.add(next);
            prev=next;pd=nd;
        }
        return out;
    }
    /**
     * Re-samples the material quad onto one polyhedron face by position (LittleTiles' VectorFan rule,
     * written for an arbitrary face plane): the polygon vertex is projected into the quad's own (a,b)
     * plane, the offset from the quad's first vertex is turned into a UV offset with the quad's own UV
     * density (s/tv below), and the polygon is clamped to the quad's bounds first.
     * <p>
     * For a full-block face this degenerates to "sample the texture where this piece of geometry sits",
     * which is what keeps a wall of polyhedra and the real blocks next to it seamless; for inset models
     * (panes, torches) the clamp keeps the texture at its true density instead of stretching it.
     * No connected-tile data is involved: the UVs describe position, not a connection state.
     */
    private static void projectFace(List<BakedQuad> output,Polyhedron.Face face,BakedQuad source,Direction dir) {
        int[] src=source.getVertices();int stride=src.length/4;
        Vec3 p0=vertex(src,stride,0),p1=vertex(src,stride,1),p3=vertex(src,stride,3);
        double a0=a(p0,dir),b0=b(p0,dir), da1=a(p1,dir)-a0,db1=b(p1,dir)-b0,da3=a(p3,dir)-a0,db3=b(p3,dir)-b0;
        double det=da1*db3-da3*db1;
        if(Math.abs(det)<1e-9)return;
        double amin=2,amax=-1,bmin=2,bmax=-1;
        for(int i=0;i<4;i++){Vec3 p=vertex(src,stride,i);amin=Math.min(amin,a(p,dir));amax=Math.max(amax,a(p,dir));bmin=Math.min(bmin,b(p,dir));bmax=Math.max(bmax,b(p,dir));}
        List<Vec3> polygon=face.vertices().stream().map(p->new Vec3(p.x()/16.0,p.y()/16.0,p.z()/16.0)).toList();
        polygon=clip(clip(clip(clip(polygon,dir,true,amin,true),dir,true,amax,false),dir,false,bmin,true),dir,false,bmax,false);
        double u0=Float.intBitsToFloat(src[4]),v0=Float.intBitsToFloat(src[5]);
        double u1=Float.intBitsToFloat(src[stride+4])-u0,v1=Float.intBitsToFloat(src[stride+5])-v0;
        double u3=Float.intBitsToFloat(src[3*stride+4])-u0,v3=Float.intBitsToFloat(src[3*stride+5])-v0;
        Polyhedron.Plane n=face.plane();double length=Math.sqrt(n.x()*n.x()+n.y()*n.y()+n.z()*n.z());
        int normal=((byte)Math.round(n.x()/length*127)&255)|(((byte)Math.round(n.y()/length*127)&255)<<8)|(((byte)Math.round(n.z()/length*127)&255)<<16);
        for(int t=1;t<polygon.size()-1;t++) {
            Vec3[] triangle={polygon.getFirst(),polygon.get(t),polygon.get(t+1),polygon.get(t+1)};
            if(triangle[1].subtract(triangle[0]).cross(triangle[2].subtract(triangle[0])).lengthSqr()<1e-16)continue;
            int[] out=new int[src.length];
            for(int i=0;i<4;i++) {
                System.arraycopy(src,0,out,i*stride,stride);
                Vec3 p=triangle[i];double da=a(p,dir)-a0,db=b(p,dir)-b0;
                double s=(da*db3-da3*db)/det,tv=(da1*db-da*db1)/det;
                out[i*stride]=Float.floatToRawIntBits((float)p.x);out[i*stride+1]=Float.floatToRawIntBits((float)p.y);out[i*stride+2]=Float.floatToRawIntBits((float)p.z);
                out[i*stride+4]=Float.floatToRawIntBits((float)(u0+s*u1+tv*u3));out[i*stride+5]=Float.floatToRawIntBits((float)(v0+s*v1+tv*v3));
                if(stride>7)out[i*stride+7]=normal;
            }
            output.add(new BakedQuad(out,source.getTintIndex(),dir,source.getSprite(),source.isShade(),source.hasAmbientOcclusion()));
        }
    }
}
