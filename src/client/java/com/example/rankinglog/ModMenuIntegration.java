package com.example.rankinglog;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

public class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        // ModMenu에서 설정 버튼을 누르면 우리가 만든 ModSettingsScreen을 열어줍니다.
        return ModSettingsScreen::new;
    }
}