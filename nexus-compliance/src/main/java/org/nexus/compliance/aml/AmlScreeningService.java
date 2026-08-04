package org.nexus.compliance.aml;

/**
 * AML 筛查服务接口。
 * <p>
 * 负责对交易、地址、用户进行反洗钱与制裁名单筛查。
 * </p>
 */
public interface AmlScreeningService {

    /**
     * 对交易进行 AML 筛查。
     *
     * @param transaction 待筛查交易
     * @return 筛查结果
     */
    ScreeningResult screen(Object transaction);

    /**
     * 对地址进行 AML 筛查。
     *
     * @param address 地址
     * @return 筛查结果
     */
    ScreeningResult screenAddress(String address);

    /**
     * 对用户进行 AML 筛查。
     *
     * @param userId 用户 ID
     * @return 筛查结果
     */
    ScreeningResult screenUser(String userId);
}