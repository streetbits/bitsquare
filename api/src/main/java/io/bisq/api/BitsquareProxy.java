package io.bisq.api;

import com.google.common.base.Strings;
import com.google.inject.Inject;
import io.bisq.api.api.*;
import io.bisq.api.api.Currency;
import io.bisq.common.app.Version;
import io.bisq.common.crypto.KeyRing;
import io.bisq.common.locale.CurrencyUtil;
import io.bisq.common.util.MathUtils;
import io.bisq.core.app.BisqEnvironment;
import io.bisq.core.btc.Restrictions;
import io.bisq.core.btc.wallet.BsqWalletService;
import io.bisq.core.btc.wallet.WalletService;
import io.bisq.core.offer.Offer;
import io.bisq.core.offer.OfferBookService;
import io.bisq.core.offer.OfferPayload;
import io.bisq.core.offer.OpenOfferManager;
import io.bisq.core.payment.*;
import io.bisq.core.provider.fee.FeeService;
import io.bisq.core.provider.price.MarketPrice;
import io.bisq.core.provider.price.PriceFeedService;
import io.bisq.core.trade.TradeManager;
import io.bisq.core.user.Preferences;
import io.bisq.core.user.User;
import io.bisq.core.util.CoinUtil;
import io.bisq.network.p2p.NodeAddress;
import io.bisq.network.p2p.P2PService;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.wallet.Wallet;

import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toList;

/**
 * This class is a proxy for all bitsquare features the api will use.
 * <p>
 * No methods/representations used in the interface layers (REST/Socket/...) should be used in this class.
 */
@Slf4j
public class BitsquareProxy {
    private WalletService walletService;
    private User user;
    private TradeManager tradeManager;
    private OpenOfferManager openOfferManager;
    private OfferBookService offerBookService;
    private P2PService p2PService;
    private KeyRing keyRing;
    private PriceFeedService priceFeedService;
    private FeeService feeService;
    private Preferences preferences;
    private BsqWalletService bsqWalletService;


    private MarketPrice marketPrice;
    private boolean marketPriceAvailable;


    public BitsquareProxy(WalletService walletService, TradeManager tradeManager, OpenOfferManager openOfferManager,
                          OfferBookService offerBookService, P2PService p2PService, KeyRing keyRing,
                          PriceFeedService priceFeedService, User user, FeeService feeService, Preferences preferences, BsqWalletService bsqWalletService) {
        this.walletService = walletService;
        this.tradeManager = tradeManager;
        this.openOfferManager = openOfferManager;
        this.offerBookService = offerBookService;
        this.p2PService = p2PService;
        this.keyRing = keyRing;
        this.priceFeedService = priceFeedService;
        this.user = user;
        this.feeService = feeService;
        this.preferences = preferences;
        this.bsqWalletService = bsqWalletService;
    }

    public CurrencyList getCurrencyList() {
        CurrencyList currencyList = new CurrencyList();
        CurrencyUtil.getAllSortedCryptoCurrencies().forEach(cryptoCurrency -> currencyList.add(cryptoCurrency.getCode(), cryptoCurrency.getName(), "crypto"));
        CurrencyUtil.getAllSortedFiatCurrencies().forEach(fiatCurrency -> currencyList.add(fiatCurrency.getCurrency().getSymbol(), fiatCurrency.getName(), "fiat"));
        Collections.sort(currencyList.currencies, (io.bisq.api.api.Currency p1, io.bisq.api.api.Currency p2) -> p1.name.compareTo(p2.name));
        return currencyList;
    }

    public MarketList getMarketList() {
        MarketList marketList = new MarketList();
        CurrencyList currencyList = getCurrencyList(); // we calculate this twice but only at startup
        //currencyList.getCurrencies().stream().flatMap(currency -> marketList.getMarkets().forEach(currency1 -> cur))
        List<Market> btc = CurrencyUtil.getAllSortedCryptoCurrencies().stream().filter(cryptoCurrency -> !(cryptoCurrency.getCode().equals("BTC"))).map(cryptoCurrency -> new Market(cryptoCurrency.getCode(), "BTC")).collect(toList());
        marketList.markets.addAll(btc);
        btc = CurrencyUtil.getAllSortedFiatCurrencies().stream().map(cryptoCurrency -> new Market("BTC", cryptoCurrency.getCode())).collect(toList());
        marketList.markets.addAll(btc);
        Collections.sort(currencyList.currencies, (io.bisq.api.api.Currency p1, Currency p2) -> p1.name.compareTo(p2.name));
        return marketList;
    }


    private List<PaymentAccount> getPaymentAccountList() {
        return new ArrayList(user.getPaymentAccounts());
    }

    public AccountList getAccountList() {
        AccountList accountList = new AccountList();
        accountList.accounts = getPaymentAccountList().stream()
                .map(paymentAccount -> new Account(paymentAccount)).collect(Collectors.toSet());
        return accountList;
    }

    public boolean offerCancel(String offerId) {
        if (Strings.isNullOrEmpty(offerId)) {
            return false;
        }
        Optional<Offer> offer = offerBookService.getOffers().stream().filter(offer1 -> offerId.equals(offer1.getId())).findAny();
        if (!offer.isPresent()) {
            return false;
        }
        // do something more intelligent here, maybe block till handler is called.
        offerBookService.removeOffer(offer.get().getOfferPayload(), () -> log.info("offer removed"), (err) -> log.error("Error removing offer: " + err));
        return true;
    }

    public Optional<OfferData> getOfferDetail(String offerId) {
        if (Strings.isNullOrEmpty(offerId)) {
            return Optional.empty();
        }
        Optional<Offer> offer = offerBookService.getOffers().stream().filter(offer1 -> offerId.equals(offer1.getId())).findAny();
        if (!offer.isPresent()) {
            return Optional.empty();
        }
        return Optional.of(new OfferData(offer.get()));
    }

    public List<OfferData> getOfferList() {
        List<OfferData> offer = offerBookService.getOffers().stream().map(offer1 -> new OfferData(offer1)).collect(toList());
        return offer;

    }

    public boolean offerMake(String market, String accountId, OfferPayload.Direction direction, BigDecimal amount, BigDecimal minAmount,
                             boolean useMarketBasedPrice, double marketPriceMargin, String currencyCode, String counterCurrencyCode, String fiatPrice) {
        // TODO: detect bad direction, bad market, no paymentaccount for user
        // PaymentAccountUtil.isPaymentAccountValidForOffer
        Optional<PaymentAccount> optionalAccount = getPaymentAccountList().stream()
                .filter(account1 -> account1.getId().equals(accountId)).findFirst();
        if(!optionalAccount.isPresent()) {
            // return an error
            log.error("Colud not find payment account with id:{}", accountId);
            return false;
        }
        PaymentAccount paymentAccount = optionalAccount.get();

        // COPIED from CreateDataOfferModel: TODO refactor uit of GUI module  /////////////////////////////
        String countryCode = paymentAccount instanceof CountryBasedPaymentAccount ? ((CountryBasedPaymentAccount) paymentAccount).getCountry().code : null;
        ArrayList<String> acceptedCountryCodes = null;
        if (paymentAccount instanceof SepaAccount) {
            acceptedCountryCodes = new ArrayList<>();
            acceptedCountryCodes.addAll(((SepaAccount) paymentAccount).getAcceptedCountryCodes());
        } else if (paymentAccount instanceof CountryBasedPaymentAccount) {
            acceptedCountryCodes = new ArrayList<>();
            acceptedCountryCodes.add(((CountryBasedPaymentAccount) paymentAccount).getCountry().code);
        }
        String bankId = paymentAccount instanceof BankAccount ? ((BankAccount) paymentAccount).getBankId() : null;
        ArrayList<String> acceptedBanks = null;
        if (paymentAccount instanceof SpecificBanksAccount) {
            acceptedBanks = new ArrayList<>(((SpecificBanksAccount) paymentAccount).getAcceptedBanks());
        } else if (paymentAccount instanceof SameBankAccount) {
            acceptedBanks = new ArrayList<>();
            acceptedBanks.add(((SameBankAccount) paymentAccount).getBankId());
        }
        long maxTradeLimit = paymentAccount.getPaymentMethod().getMaxTradeLimitAsCoin(currencyCode).value;
        long maxTradePeriod = paymentAccount.getPaymentMethod().getMaxTradePeriod();
        boolean isPrivateOffer = false;
        boolean useAutoClose = false;
        boolean useReOpenAfterAutoClose = false;
        long lowerClosePrice = 0;
        long upperClosePrice = 0;
        String hashOfChallenge = null;
        HashMap<String, String> extraDataMap = null;

        // COPIED from CreateDataOfferModel /////////////////////////////

        updateMarketPriceAvailable(currencyCode);

        // TODO there are a lot of dummy values in this constructor !!!
        Coin coinAmount = Coin.valueOf(amount.longValueExact());
        OfferPayload offerPayload = new OfferPayload(
                UUID.randomUUID().toString(),
                new Date().getTime(),
                p2PService.getAddress(),
                keyRing.getPubKeyRing(),
                direction,
                Long.valueOf(fiatPrice),
                marketPriceMargin,
                useMarketBasedPrice,
                amount.longValueExact(),
                minAmount.longValueExact(),
                currencyCode,
                counterCurrencyCode,
                (ArrayList<NodeAddress>) user.getAcceptedArbitratorAddresses(),
                (ArrayList<NodeAddress>) user.getAcceptedMediatorAddresses(),
                paymentAccount.getPaymentMethod().getId(),
                paymentAccount.getId(),
                null, // "TO BE FILLED IN", // offerfeepaymenttxid ???
                countryCode,
                acceptedCountryCodes,
                bankId,
                acceptedBanks,
                Version.VERSION,
                walletService.getLastBlockSeenHeight(),
                feeService.getTxFee(600).value,
                getMakerFee(coinAmount, marketPriceMargin).value,
                preferences.getPayFeeInBtc() || !isBsqForFeeAvailable(coinAmount, marketPriceMargin),
                preferences.getBuyerSecurityDepositAsCoin().value,
                Restrictions.getSellerSecurityDeposit().value,
                maxTradeLimit,
                maxTradePeriod,
                useAutoClose,
                useReOpenAfterAutoClose,
                upperClosePrice,
                lowerClosePrice,
                isPrivateOffer,
                hashOfChallenge,
                extraDataMap,
                Version.TRADE_PROTOCOL_VERSION
                );

        Offer offer = new Offer(offerPayload); // priceFeedService);

        try {
            // TODO subtract OfferFee: .subtract(FeePolicy.getCreateOfferFee())
            openOfferManager.placeOffer(offer, Coin.valueOf(amount.longValue()),
                    true, (transaction) -> log.info("Result is " + transaction));
        } catch(Throwable e) {
            return false;
        }
        return true;
    }

    /// START TODO refactor out of GUI module ////

    boolean isBsqForFeeAvailable(Coin amount, double marketPriceMargin) {
        return BisqEnvironment.isBaseCurrencySupportingBsq() &&
                getMakerFee(false, amount, marketPriceMargin) != null &&
                bsqWalletService.getAvailableBalance() != null &&
                getMakerFee(false, amount, marketPriceMargin) != null &&
                !bsqWalletService.getAvailableBalance().subtract(getMakerFee(false, amount, marketPriceMargin)).isNegative();
    }

    boolean isCurrencyForMakerFeeBtc(Coin amount, double marketPriceMargin) {
        return preferences.getPayFeeInBtc() || !isBsqForFeeAvailable(amount, marketPriceMargin);
    }

    private void updateMarketPriceAvailable(String baseCurrencyCode) {
        marketPrice = priceFeedService.getMarketPrice(baseCurrencyCode);
        marketPriceAvailable = (marketPrice != null );
    }

    @Nullable
    public Coin getMakerFee(Coin amount, double marketPriceMargin) {
        return getMakerFee(isCurrencyForMakerFeeBtc(amount, marketPriceMargin), amount, marketPriceMargin);
    }

    @Nullable
    Coin getMakerFee(boolean isCurrencyForMakerFeeBtc, Coin amount, double marketPriceMargin) {
        if (amount != null) {
            final Coin feePerBtc = CoinUtil.getFeePerBtc(FeeService.getMakerFeePerBtc(isCurrencyForMakerFeeBtc), amount);
            double makerFeeAsDouble = (double) feePerBtc.value;
            if (marketPriceAvailable) {
                if (marketPriceMargin > 0)
                    makerFeeAsDouble = makerFeeAsDouble * Math.sqrt(marketPriceMargin * 100);
                else
                    makerFeeAsDouble = 0;
                // For BTC we round so min value change is 100 satoshi
                if (isCurrencyForMakerFeeBtc)
                    makerFeeAsDouble = MathUtils.roundDouble(makerFeeAsDouble / 100, 0) * 100;
            }

            return CoinUtil.maxCoin(Coin.valueOf(MathUtils.doubleToLong(makerFeeAsDouble)), FeeService.getMinMakerFee(isCurrencyForMakerFeeBtc));
        } else {
            return null;
        }
    }

    /// STOP TODO refactor out of GUI module ////


    public WalletDetails getWalletDetails() {
        if (!walletService.isWalletReady()) {
            return null;
        }

        Coin availableBalance = walletService.getAvailableBalance();
        Coin reservedBalance = walletService.getBalance(Wallet.BalanceType.ESTIMATED_SPENDABLE);
        return new WalletDetails(availableBalance.toPlainString(), reservedBalance.toPlainString());
    }

    public WalletTransactions getWalletTransactions(long start, long end, long limit) {
        boolean includeDeadTransactions = false;
        Set<Transaction> transactions = walletService.getTransactions(includeDeadTransactions);
        WalletTransactions walletTransactions = new WalletTransactions();
        List<WalletTransaction> transactionList = walletTransactions.getTransactions();

        for (Transaction t : transactions) {
//            transactionList.add(new WalletTransaction(t.getValue(walletService.getWallet().getTransactionsByTime())))
        }
        return null;
    }

    public List<WalletAddress> getWalletAddresses() {
        return user.getPaymentAccounts().stream()
                .filter(paymentAccount -> paymentAccount instanceof CryptoCurrencyAccount)
                .map(paymentAccount -> (CryptoCurrencyAccount) paymentAccount)
                .map(paymentAccount -> new WalletAddress(((CryptoCurrencyAccount) paymentAccount).getId(), paymentAccount.getPaymentMethod().toString(), ((CryptoCurrencyAccount) paymentAccount).getAddress()))
                .collect(toList());
    }
}