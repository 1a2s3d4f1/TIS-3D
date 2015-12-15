package li.cil.tis3d.common.module;

import li.cil.tis3d.api.machine.Casing;
import li.cil.tis3d.api.machine.Face;
import li.cil.tis3d.api.machine.Pipe;
import li.cil.tis3d.api.machine.Port;
import li.cil.tis3d.api.prefab.module.AbstractModule;
import li.cil.tis3d.api.util.RenderUtil;
import li.cil.tis3d.client.render.TextureLoader;
import li.cil.tis3d.common.network.Network;
import li.cil.tis3d.common.network.message.MessageParticleEffect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.NoteBlockEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * The audio module, emitting sounds like none other.
 */
public final class ModuleAudio extends AbstractModule {
    // --------------------------------------------------------------------- //
    // Computed data

    /**
     * Resolve instrument ID to name of sound used for instrument.
     */
    private static final String[] INSTRUMENT_SOUND_NAMES = new String[]{"note.harp", "note.bd", "note.snare", "note.hat", "note.bassattack"};

    /**
     * The last tick we made a sound. Used to avoid emitting multiple sounds
     * per tick when overclocked, because that could quickly spam a lot of
     * packets, and sound horrible, too.
     */
    private long lastStep = 0L;

    // --------------------------------------------------------------------- //

    public ModuleAudio(final Casing casing, final Face face) {
        super(casing, face);
    }

    // --------------------------------------------------------------------- //
    // Module

    @Override
    public void step() {
        stepInput();

        lastStep = getCasing().getCasingWorld().getTotalWorldTime();
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void render(final boolean enabled, final float partialTicks) {
        if (!enabled) {
            return;
        }

        GlStateManager.enableBlend();

        Minecraft.getMinecraft().getTextureManager().bindTexture(TextureMap.locationBlocksTexture);
        final TextureAtlasSprite icon = Minecraft.getMinecraft().getTextureMapBlocks().getAtlasSprite(TextureLoader.LOCATION_MODULE_AUDIO_OVERLAY.toString());
        RenderUtil.drawQuad(icon.getMinU(), icon.getMinV(), icon.getMaxU(), icon.getMaxV());

        GlStateManager.disableBlend();
    }

    // --------------------------------------------------------------------- //

    /**
     * Update the input of the module, reading the type of note to play.
     */
    private void stepInput() {
        for (final Port port : Port.VALUES) {
            // Continuously read from all ports, emit packet when receiving a value.
            final Pipe receivingPipe = getCasing().getReceivingPipe(getFace(), port);
            if (!receivingPipe.isReading()) {
                receivingPipe.beginRead();
            }
            if (receivingPipe.canTransfer()) {
                // Don't actually read more values if we already sent a packet this tick.
                if (getCasing().getCasingWorld().getTotalWorldTime() > lastStep) {
                    playNote(receivingPipe.read());

                    // Start reading again right away to read as fast as possible.
                    receivingPipe.beginRead();
                }
            }
        }
    }

    /**
     * Decode the specified value into instrument, note and volume and play it.
     *
     * @param value the value defining the sound to play.
     */
    private void playNote(final int value) {
        final int noteId = (value & 0xFF00) >>> 8;
        final int volume = Math.min(4, (value & 0x00F0) >>> 4);
        final int instrumentId = value & 0x000F;

        // Skip mute sounds.
        if (volume < 1) {
            return;
        }

        // Send event to check if the sound may be played / should be modulated.
        final World world = getCasing().getCasingWorld();
        final BlockPos pos = getCasing().getPosition();
        final NoteBlockEvent.Play event = new NoteBlockEvent.Play(world, pos, world.getBlockState(pos), noteId, instrumentId);
        if (!MinecraftForge.EVENT_BUS.post(event)) {
            // Not cancelled, get pitch, sound effect name.
            final int note = event.getVanillaNoteId();
            final float pitch = (float) Math.pow(2, (note - 12) / 12.0);
            final String sound = INSTRUMENT_SOUND_NAMES[event.instrument.ordinal()];

            // Offset to have the actual origin be in front of the module.
            final EnumFacing facing = Face.toEnumFacing(getFace());
            final double x = pos.getX() + 0.5 + facing.getFrontOffsetX() * 0.6;
            final double y = pos.getY() + 0.5 + facing.getFrontOffsetY() * 0.6;
            final double z = pos.getZ() + 0.5 + facing.getFrontOffsetZ() * 0.6;

            // Let there be sound!
            world.playSoundEffect(x, y, z, sound, volume, pitch);
            final MessageParticleEffect message = new MessageParticleEffect(world, EnumParticleTypes.NOTE, x, y, z);
            final NetworkRegistry.TargetPoint target = Network.getTargetPoint(world, x, y, z, Network.RANGE_LOW);
            Network.INSTANCE.getWrapper().sendToAllAround(message, target);
        }
    }
}
