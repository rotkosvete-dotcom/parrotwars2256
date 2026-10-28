package foxgirl.client;

import com.mojang.blaze3d.vertex.PoseStack;
import foxgirl.FoxGirlEntity;
import foxgirl.FoxGirlMod;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;

public class FoxGirlRenderer extends MobRenderer<FoxGirlEntity, FoxGirlModel> {
	private static final ResourceLocation TEXTURE = new ResourceLocation(FoxGirlMod.MOD_ID, "textures/entity/foxgirl.png");

	public FoxGirlRenderer(EntityRendererProvider.Context ctx) {
		super(ctx, new FoxGirlModel(ctx.getResourceManager()), 0.4f);
	}

	@Override
	protected void scale(FoxGirlEntity entity, PoseStack poseStack, float partialTick) {
		float s = entity.getRenderScale();
		poseStack.scale(s, s, s);
		this.shadowRadius = 0.4f * s;
	}

	@Override
	public ResourceLocation getTextureLocation(FoxGirlEntity entity) {
		return TEXTURE;
	}
}
