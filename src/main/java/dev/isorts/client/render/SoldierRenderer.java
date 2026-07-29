package dev.isorts.client.render;

import dev.isorts.unit.SoldierEntity;
import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.util.Identifier;

/**
 * Draws soldiers as humans, using Minecraft's own player model and stock skins.
 * <p>
 * ponytail: no model file, no texture file, no model-layer registration. The vanilla player model
 * layer is already loaded and its geometry is exactly what we want, so the model is borrowed
 * ({@code getPart(EntityModelLayers.PLAYER)}) and the textures are the game's own default skins.
 * Extending {@link BipedEntityRenderer} rather than the bare mob renderer is what gets working
 * arm poses and a visible weapon in hand for free.
 * <p>
 * Registering a renderer at all is the whole point: an entity type with no renderer does not just
 * fail to draw, it crashes Iris - the shadow pass asks for the renderer and gets null.
 */
public class SoldierRenderer
        extends BipedEntityRenderer<SoldierEntity, SoldierRenderState, BipedEntityModel<SoldierRenderState>> {

    private static final Identifier[] TEXTURES = new Identifier[SoldierEntity.SKINS.length];

    static {
        for (int i = 0; i < SoldierEntity.SKINS.length; i++) {
            TEXTURES[i] = Identifier.ofVanilla("textures/entity/player/wide/" + SoldierEntity.SKINS[i] + ".png");
        }
    }

    public SoldierRenderer(EntityRendererFactory.Context context) {
        super(context, new BipedEntityModel<>(context.getPart(EntityModelLayers.PLAYER)), 0.5f);
    }

    @Override
    public SoldierRenderState createRenderState() {
        return new SoldierRenderState();
    }

    @Override
    public void updateRenderState(SoldierEntity entity, SoldierRenderState state, float tickDelta) {
        super.updateRenderState(entity, state, tickDelta);
        state.texture = TEXTURES[entity.skinIndex()];
    }

    @Override
    public Identifier getTexture(SoldierRenderState state) {
        return state.texture != null ? state.texture : TEXTURES[0];
    }
}
