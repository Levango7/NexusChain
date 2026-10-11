package org.nexus.gateway.fundreport;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MerchantPayoutAccountRepository extends JpaRepository<MerchantPayoutAccount, Long> {

    List<MerchantPayoutAccount> findByMerchantId(Long merchantId);

    Optional<MerchantPayoutAccount> findByMerchantIdAndStatus(Long merchantId,
                                                              MerchantPayoutAccount.AccountStatus status);

    Optional<MerchantPayoutAccount> findByMerchantIdAndAccountType(Long merchantId,
                                                                    MerchantPayoutAccount.AccountType accountType);
}
