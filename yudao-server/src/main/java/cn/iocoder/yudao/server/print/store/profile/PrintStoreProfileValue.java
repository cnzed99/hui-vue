package cn.iocoder.yudao.server.print.store.profile;

/** Editable data only. Store name lives solely in print_store; nullable values mean unconfigured. */
public record PrintStoreProfileValue(String storeName, String notice,
        String provinceCode, String provinceName, String cityCode, String cityName,
        String districtCode, String districtName, String streetCode, String streetName,
        String address, String contact, String telephone, String qq,
        String openTime, String closeTime, Boolean businessOpen) { }
