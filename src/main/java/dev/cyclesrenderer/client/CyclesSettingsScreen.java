package dev.cyclesrenderer.client;

import dev.cyclesrenderer.config.CyclesClientConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModContainer;

import java.util.EnumMap;
import java.util.Map;

public final class CyclesSettingsScreen extends Screen {
    private static final int HEADER_HEIGHT = 48;
    private static final int FOOTER_HEIGHT = 34;
    private final Screen parent;
    private final CyclesClientConfig.Draft draft = CyclesClientConfig.draft();
    private final Map<CyclesClientConfig.Category, Button> categoryButtons =
            new EnumMap<>(CyclesClientConfig.Category.class);
    private CyclesClientConfig.Category selectedCategory = CyclesClientConfig.Category.OUTPUT;
    private CyclesSettingsList settingsList;
    private EditBox searchBox;
    private String searchQuery = "";

    public CyclesSettingsScreen(ModContainer ignoredModContainer, Screen parent) {
        super(Component.translatable("screen.cyclesrenderer.settings.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        categoryButtons.clear();
        int availableHeight = Math.max(80, height - HEADER_HEIGHT - FOOTER_HEIGHT);
        int categoryColumns = availableHeight < 240 ? 2 : 1;
        int categoryWidth = Math.clamp(width / 7, 92, 138);
        int categoryPaneWidth = categoryColumns * categoryWidth + (categoryColumns - 1) * 4;
        int left = 8;
        int categoryTop = HEADER_HEIGHT;

        CyclesClientConfig.Category[] categories = CyclesClientConfig.Category.values();
        int rows = (categories.length + categoryColumns - 1) / categoryColumns;
        for (int index = 0; index < categories.length; index++) {
            CyclesClientConfig.Category category = categories[index];
            int column = index / rows;
            int row = index % rows;
            Button button = addRenderableWidget(Button.builder(
                            categoryLabel(category), clicked -> selectCategory(category))
                    .bounds(left + column * (categoryWidth + 4), categoryTop + row * 22,
                            categoryWidth, 20)
                    .build());
            categoryButtons.put(category, button);
        }

        int contentLeft = left + categoryPaneWidth + 8;
        int contentWidth = Math.max(160, width - contentLeft - 8);
        searchBox = addRenderableWidget(new EditBox(
                font, contentLeft, 22, contentWidth, 20,
                Component.translatable("screen.cyclesrenderer.settings.search")));
        searchBox.setHint(Component.translatable("screen.cyclesrenderer.settings.search"));
        searchBox.setValue(searchQuery);
        searchBox.setResponder(value -> {
            searchQuery = value;
            rebuildSettingsList();
        });

        settingsList = addRenderableWidget(new CyclesSettingsList(
                minecraft, contentWidth, availableHeight, contentLeft, HEADER_HEIGHT, draft));

        int footerY = height - 26;
        int footerButtonWidth = Math.min(110, Math.max(72, (contentWidth - 8) / 3));
        int footerLeft = contentLeft + Math.max(0, contentWidth - footerButtonWidth * 3 - 8);
        addRenderableWidget(Button.builder(
                        Component.translatable("screen.cyclesrenderer.settings.apply"),
                        button -> applyDraft())
                .bounds(footerLeft, footerY, footerButtonWidth, 20)
                .build());
        addRenderableWidget(Button.builder(
                        Component.translatable("screen.cyclesrenderer.settings.discard"),
                        button -> discardDraft())
                .bounds(footerLeft + footerButtonWidth + 4, footerY, footerButtonWidth, 20)
                .build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .bounds(footerLeft + (footerButtonWidth + 4) * 2, footerY,
                        footerButtonWidth, 20)
                .build());

        selectCategory(selectedCategory);
    }

    @Override
    public void extractRenderState(
            GuiGraphicsExtractor graphics,
            int mouseX,
            int mouseY,
            float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, title, width / 2, 7, 0xFFFFFFFF);
    }

    @Override
    public void onClose() {
        draft.apply();
        minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void selectCategory(CyclesClientConfig.Category category) {
        selectedCategory = category;
        for (Map.Entry<CyclesClientConfig.Category, Button> entry : categoryButtons.entrySet()) {
            entry.getValue().setMessage(categoryLabel(entry.getKey(), entry.getKey() == category));
        }
        rebuildSettingsList();
    }

    private void rebuildSettingsList() {
        if (settingsList != null) {
            settingsList.rebuild(selectedCategory, searchQuery);
        }
    }

    private void applyDraft() {
        draft.apply();
        rebuildSettingsList();
    }

    private void discardDraft() {
        draft.discard();
        rebuildSettingsList();
    }

    private static Component categoryLabel(CyclesClientConfig.Category category) {
        return categoryLabel(category, false);
    }

    private static Component categoryLabel(
            CyclesClientConfig.Category category,
            boolean selected) {
        String key = "screen.cyclesrenderer.settings.category."
                + category.name().toLowerCase(java.util.Locale.ROOT);
        Component label = Component.translatable(key);
        return selected ? Component.literal("▶ ").append(label) : label;
    }
}
