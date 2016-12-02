package com.roysolberg.java.hdlserver.hdl.component;

import java.util.HashMap;
import java.util.Map;

public class DlpComponent extends HdlComponent {

    protected final int numOfButtons;
    protected final boolean hasImages;
    protected final int numOfPages;
    protected final boolean hasHeating;
    protected boolean isHeatingActive = true;
    protected final boolean hasCooling;
    protected boolean isCoolingActive = true;
    protected final boolean hasMusic;
    protected boolean isMusicActive = true;
    protected Map<Integer, Boolean> activePages = new HashMap<>();

    public DlpComponent(int subnet, int deviceId, int deviceType, String remark, int numOfButtons, boolean hasImages, int numOfPages, boolean hasHeating, boolean hasCooling, boolean hasMusic) {
        super(subnet, deviceId, deviceType, remark);
        this.numOfButtons = numOfButtons;
        this.hasImages = hasImages;
        this.numOfPages = numOfPages;
        this.hasHeating = hasHeating;
        this.hasCooling = hasCooling;
        this.hasMusic = hasMusic;
        for (int i = 0; i < numOfPages; i++) {
            activePages.put(i, true);
        }
    }

    @Override
    public String getDescription() {
        return "DLP Panel with AC Music Clock Floor Heating";
    }

    @Override
    public String getDeviceGroup() {
        return "panel";
    }

    @Override
    public int getDefaultSortOrder() {
        return 100;
    }

    public boolean hasHeating() {
        return hasHeating;
    }

    public void setHeatingActive(boolean heatingActive) {
        this.isHeatingActive = heatingActive;
    }

    public boolean hasMusic() {
        return hasMusic;
    }

    public void setMusicActive(boolean musicActive) {
        this.isMusicActive = musicActive;
    }

    public boolean hasCooling() {
        return hasCooling;
    }

    public void setCoolingActive(boolean coolingActive) {
        isCoolingActive = coolingActive;
    }

    public void setPageActive(int pageNo, boolean active) {
        activePages.put(pageNo, active);
    }

    public boolean isPageActive(int pageNo) {
        // TODO: Remove exception handling when we know what this is:
        try {
            return activePages.get(pageNo);
        } catch (NullPointerException e) {
            return true;
        }
    }

    public int getNumOfActivePages(boolean includeHeatingCoolingMusic) {
        int numOfActivePages = 0;
        if (includeHeatingCoolingMusic) {
            if (hasHeating && isHeatingActive) {
                numOfActivePages++;
            }
            if (hasCooling && isCoolingActive) {
                numOfActivePages++;
            }
            if (hasMusic && isMusicActive) {
                numOfActivePages++;
            }
        }
        for (Boolean activePage : activePages.values()) {
            if (activePage) {
                numOfActivePages++;
            }
        }
        return numOfActivePages;
    }

    public boolean isHeatingActive() {
        return hasHeating && isHeatingActive;
    }

    public boolean isCoolingActive() {
        return hasCooling && isCoolingActive;
    }

    public boolean isMusicActive() {
        return hasMusic && isMusicActive;
    }

}
