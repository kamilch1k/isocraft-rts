package dev.isorts.client.render;

import net.minecraft.client.render.entity.state.BipedEntityRenderState;
import net.minecraft.util.Identifier;

/** Biped render state plus which skin this particular soldier wears. */
public class SoldierRenderState extends BipedEntityRenderState {
    public Identifier texture;
}
