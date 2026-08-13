package dev.cyclesrenderer.client;

import dev.cyclesrenderer.config.CyclesClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

final class CyclesSettingsList
        extends ContainerObjectSelectionList<CyclesSettingsList.SettingEntry> {
    private final CyclesClientConfig.Draft draft;

    CyclesSettingsList(
            Minecraft minecraft,
            int width,
            int height,
            int x,
            int y,
            CyclesClientConfig.Draft draft) {
        super(minecraft, width, height, y, 24);
        this.draft = draft;
        this.centerListVertically = false;
        updateSizeAndPosition(width, height, x, y);
    }

    void rebuild(CyclesClientConfig.Category category, String query) {
        clearEntries();
        String needle = query.strip().toLowerCase(Locale.ROOT);
        for (CyclesClientConfig.ConfigOption<?> option : CyclesClientConfig.options()) {
            String translated = Component.translatable(option.translationKey()).getString();
            boolean matchesSearch = needle.isEmpty()
                    || option.id().toLowerCase(Locale.ROOT).contains(needle)
                    || translated.toLowerCase(Locale.ROOT).contains(needle);
            if (option.category() == category && matchesSearch) {
                addEntry(new SettingEntry(translated, option.kind().name()));
            }
        }
        setScrollAmount(0.0D);
    }

    @Override
    public int getRowWidth() {
        return Math.max(120, getWidth() - 18);
    }

    static final class SettingEntry extends ContainerObjectSelectionList.Entry<SettingEntry> {
        private final StringWidget label;
        private final StringWidget valueKind;

        SettingEntry(String label, String valueKind) {
            this.label = new StringWidget(Component.literal(label), Minecraft.getInstance().font);
            this.valueKind = new StringWidget(Component.literal(valueKind), Minecraft.getInstance().font)
                    .setMaxWidth(72);
        }

        @Override
        public void extractContent(
                GuiGraphicsExtractor graphics,
                int mouseX,
                int mouseY,
                boolean hovered,
                float partialTick) {
            int y = getContentY() + 2;
            label.setPosition(getContentX() + 4, y);
            label.setMaxWidth(Math.max(40, getContentWidth() - 88));
            valueKind.setPosition(getContentX() + getContentWidth() - 76, y);
            label.extractRenderState(graphics, mouseX, mouseY, partialTick);
            valueKind.extractRenderState(graphics, mouseX, mouseY, partialTick);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(label, valueKind);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(label, valueKind);
        }
    }
}
