package org.homio.app.manager;

import java.awt.image.BufferedImage;
import java.net.URL;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.homio.api.AddonEntrypoint;
import org.homio.api.EntityContext;
import org.homio.api.exception.NotFoundException;
import org.homio.app.manager.common.EntityContextImpl;
import org.homio.app.spring.ContextCreated;
import org.homio.app.spring.ContextRefreshed;
import org.homio.app.utils.color.ColorThief;
import org.homio.app.utils.color.MMCQ;
import org.homio.app.utils.color.RGBUtil;
import org.springframework.stereotype.Component;

@Log4j2
@Component
@RequiredArgsConstructor
public class AddonService implements ContextCreated, ContextRefreshed {

    // constructor parameters
    private final EntityContext entityContext;
    private Map<String, String> addonColorMap;
    private Map<String, AddonEntrypoint> addonMap;
    private Collection<AddonEntrypoint> addonEntrypoints;

    @Override
    public void onContextCreated(EntityContextImpl entityContext) throws Exception {
        onContextRefresh();
    }

    @Override
    public void onContextRefresh() throws Exception {
        this.addonEntrypoints = entityContext.getBeansOfType(AddonEntrypoint.class);
        this.addonMap = addonEntrypoints.stream().collect(Collectors.toMap(AddonEntrypoint::getAddonId, s -> s));
        this.addonColorMap = new HashMap<>();

        for (AddonEntrypoint addonEntrypoint : addonEntrypoints) {
            try {
                URL imageURL = addonEntrypoint.getAddonImageURL();
                BufferedImage img = ImageIO.read(Objects.requireNonNull(imageURL));
                MMCQ.CMap result = ColorThief.getColorMap(img, 5);
                MMCQ.VBox dominantColor = result.vboxes.get(addonEntrypoint.getAddonImageColorIndex().ordinal());
                int[] rgb = dominantColor.avg(false);
                addonColorMap.put(addonEntrypoint.getAddonId(), RGBUtil.toRGBHexString(rgb));
            } catch (Exception ex) {
                log.error("Unable to start app due error in addon: <{}>", addonEntrypoint.getAddonId(), ex);
                throw ex;
            }
        }
    }

    public AddonEntrypoint findAddonEntrypoint(String addonID) {
        return addonMap.get(addonID);
    }

    public AddonEntrypoint getAddon(String addonID) {
        AddonEntrypoint addonEntrypoint = addonMap.get(addonID);
        if (addonEntrypoint == null) {
            throw new NotFoundException("Unable to find addon: " + addonID);
        }
        return addonEntrypoint;
    }

    public String getAddonColor(String addonID) {
        return addonColorMap.get(addonID);
    }

    public Collection<AddonEntrypoint> getAddons() {
        return addonEntrypoints;
    }
}
