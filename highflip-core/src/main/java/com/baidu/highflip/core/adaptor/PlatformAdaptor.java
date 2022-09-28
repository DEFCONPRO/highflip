package com.baidu.highflip.core.adaptor;

import java.util.List;

public interface PlatformAdaptor {
    String getCompany();

    String getProduct();

    String getVersion();

    List<String> getCompatibleList();

    List<String> getFeatures();
}