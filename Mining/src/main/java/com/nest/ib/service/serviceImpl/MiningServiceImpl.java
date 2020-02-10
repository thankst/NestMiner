package com.nest.ib.service.serviceImpl;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.nest.ib.contract.ERC20;
import com.nest.ib.contract.OfferContract;
import com.nest.ib.service.MiningService;
import com.nest.ib.utils.HttpClientUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Numeric;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * ClassName:MiningServiceImpl
 * Description:
 */
@Service
public class MiningServiceImpl implements MiningService {
    private static final Logger log = LoggerFactory.getLogger(MiningServiceImpl.class);
    // 访问的以太坊节点
    private static final String NODE = "https://mainnet.infura.io/v3/648e6b8ae3dc453c8acf664519577c6b";
    // ETH计量单为
    private static final BigDecimal ETH_UNIT = new BigDecimal("1000000000000000000");
    // 报价操作的input数据
    private static final String INPUT_OFFER = "0xf6a4932f";
    // 取回操作topic记录
    private static final String TRANSACTION_TOPICS_CONTRACT = "0xccacfd869caa3e2e845afe470f00dcb777e77639814c6c96bb320b69885e63ce"; // 取回的交易日志
    // 报价ETH数量
    private static BigDecimal ETH_AMOUNT = new BigDecimal("10");
    // 是否开启挖矿
    private static boolean START_MINING = false;
    // 报价工厂合约地址
    private static final String OFFER_FACTORY_CONTRACT = "0x4F391C202a906EED9e2b63fDd387F28E952782E2";  // 报价工厂合约地址
    // USDT代币合约地址
    private static final String USDT_TOKEN_ADDRESS = "0xdac17f958d2ee523a2206206994597c13d831ec7";  // USDT代币地址
    // 区块间隔数量,默认60
    private static int DEFAULT_BLOCK_INTERVAL = 60;
    private static BigInteger gasLimit = new BigInteger("1100000");
    // 私钥,需要先传入,才能启动
    private static String USER_PRIVATE_KEY = "";
    // 报价工厂API: 来源于etherscan
    private static String ETHERSCAN_OFFER_CONTRACT_API = "http://api-cn.etherscan.com/api?module=account&action=txlist&address=0x4F391C202a906EED9e2b63fDd387F28E952782E2&startblock=0&endblock=99999999&page=1&offset=10&sort=desc&apikey=YourApiKeyToken";
    // USDT/ETH价格: 来源于火币交易所API
    private static final String URL_USDT_ETH_PRICE = "https://api.huobi.pro/market/history/trade?symbol=ethusdt&size=1";

    /**
     *  开启报价
     */
    @Override
    public void offer() {
        if(!START_MINING)return;    // 检查是否设置了开启矿机
        try {
            Web3j web3j = Web3j.build(new HttpService(NODE));
            approveToOfferFactoryContract(web3j);   // 一次性授权
            sendUSDTOffer(ETH_AMOUNT,web3j);        // 发起USDT报价
        } catch (Exception e) {
            return;
        }
    }

    // 发送USDT-ETH的报价
    @Override
    public void sendUSDTOffer(BigDecimal ethAmount,Web3j web3j) throws Exception {
        BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice().multiply(new BigInteger("2"));
        // 获取USDT/ETH价格
        BigDecimal exchangePrice = getExchangePrice();
        if(exchangePrice == null){
            log.info("访问火币交易所API失败");
            return;
        }
        BigDecimal n = new BigDecimal(String.valueOf(DEFAULT_BLOCK_INTERVAL));
        BigDecimal usdtAmount = exchangePrice.multiply(new BigDecimal("1000000")).setScale(0,BigDecimal.ROUND_DOWN);
        usdtAmount = usdtAmount.multiply(ethAmount);
        Credentials credentials = Credentials.create(USER_PRIVATE_KEY);
        // 检测ETH,USDT余额是否
        BigInteger ethBalance = web3j.ethGetBalance(credentials.getAddress(), DefaultBlockParameterName.LATEST).send().getBalance();
        BigInteger usdtBalance = ERC20.load(USDT_TOKEN_ADDRESS, web3j, credentials, new BigInteger("10000000000"), new BigInteger("500000")).balanceOf(credentials.getAddress()).send();
        BigInteger ETH_AMOUNT = new BigInteger(String.valueOf(ETH_UNIT.multiply(ethAmount)));
        if(ethBalance.compareTo(new BigInteger(String.valueOf(ETH_AMOUNT))) < 0 && new BigDecimal(usdtBalance).compareTo(usdtAmount) < 0){
            log.info("账户余额不足");
            return;
        }
        BigDecimal multiply = ETH_UNIT.multiply(ethAmount).multiply(new BigDecimal("1.01")).setScale(0,BigDecimal.ROUND_DOWN);
        BigInteger nonce = web3j.ethGetTransactionCount(credentials.getAddress(), DefaultBlockParameterName.LATEST).send().getTransactionCount();
        BigInteger blockNumber = web3j.ethBlockNumber().send().getBlockNumber();
        // 检测是否满足间隔的区块数量
        boolean b;
        try {
            b = intervalBlock(blockNumber, new BigInteger(String.valueOf(n)));
        }catch (Exception e){
            return;
        }
        if(b){
            log.info("报价ETH数量：" + ETH_AMOUNT + " 报价USDT数量：" + usdtAmount);
            Function function = new Function(
                    "offer",
                    Arrays.<Type>asList(
                            new Uint256(ETH_AMOUNT),
                            new Uint256(new BigInteger(String.valueOf(usdtAmount))),
                            new Address(USDT_TOKEN_ADDRESS)),
                    Collections.<TypeReference<?>>emptyList());
            String encode = FunctionEncoder.encode(function);
            RawTransaction rawTransaction = RawTransaction.createTransaction(
                    nonce,
                    gasPrice,
                    gasLimit,
                    OFFER_FACTORY_CONTRACT,
                    new BigInteger(String.valueOf(multiply)),
                    encode);
            byte[] signedMessage = TransactionEncoder.signMessage(rawTransaction,credentials);
            String hexValue = Numeric.toHexString(signedMessage);
            String transactionHash = web3j.ethSendRawTransaction(hexValue).sendAsync().get().getTransactionHash();
            log.info("报价成功： " + transactionHash);
            Thread.sleep(1000*60*8);
        }
        turnOut(); // 取回资产
    }

    /**
     *  取回报价资产
     */
    @Override
    public void turnOut() throws Exception {
        String offerContractAddress = getOfferContractAddress();
        if(offerContractAddress == null)return;
        Web3j web3j = Web3j.build(new HttpService(NODE));
        BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice().multiply(new BigInteger("2"));
        Credentials credentials = Credentials.create(USER_PRIVATE_KEY);
        BigInteger nonce = web3j.ethGetTransactionCount(credentials.getAddress(), DefaultBlockParameterName.LATEST).send().getTransactionCount();
        // 检查是否领取过
        OfferContract offerContract = OfferContract.load(offerContractAddress,web3j,credentials,gasPrice,new BigInteger("1000000"));
        Boolean b = offerContract.checkHadReceive().send();
        if(!b){ // 如果还未领取
            BigInteger gasLimit = new BigInteger("500000");
            Function function = new Function(
                    "turnOut",
                    Arrays.<Type>asList(new Address(offerContractAddress)),
                    Collections.<TypeReference<?>>emptyList());
            String encode = FunctionEncoder.encode(function);
            RawTransaction rawTransaction = RawTransaction.createTransaction(
                    nonce,
                    gasPrice,
                    gasLimit,
                    OFFER_FACTORY_CONTRACT,
                    encode);
            byte[] signedMessage = TransactionEncoder.signMessage(rawTransaction,credentials);
            String hexValue = Numeric.toHexString(signedMessage);
            String transactionHash = web3j.ethSendRawTransaction(hexValue).sendAsync().get().getTransactionHash();
            log.info("取回hash： " + transactionHash);
            Thread.sleep(1000*60*2);
            return;
        }
    }

    /**
    * 更新区块间隔数量
    */
    @Override
    public void updateIntervalBlock(int blockInterval) {
        DEFAULT_BLOCK_INTERVAL = blockInterval;
    }
    /**
     *  查看当前的区块间隔数量
     */
    @Override
    public int selectIntervalBlock() {
        return DEFAULT_BLOCK_INTERVAL;
    }
    /**
     *  启动/关闭挖矿. true启动,false关闭
     */
    @Override
    public void startMining() {
        START_MINING = START_MINING == true ? false : true;
    }
    /**
     *  查看当前矿机是否是启动状态
     */
    @Override
    public boolean selectStartMining() {
        return START_MINING;
    }
    /**
     *  更新用户私钥
     */
    @Override
    public void updateUserPrivateKey(String privateKey){
        USER_PRIVATE_KEY = privateKey;
    }
    /**
     *  查看用户私钥
     */
    @Override
    public String selectUserWalletAddress(){
        return USER_PRIVATE_KEY.equalsIgnoreCase("") ? null : Credentials.create(USER_PRIVATE_KEY).getAddress();
    }
    /**
    * 检测是否满足间隔区块数量
    */
    private static boolean intervalBlock(BigInteger blockNumber,BigInteger interval){
        String s;
        try {
            s = HttpClientUtil.sendHttpGet(ETHERSCAN_OFFER_CONTRACT_API);
        }catch (Exception e){
            return false;
        }
        JSONObject data = JSONObject.parseObject(s);
        JSONArray result = data.getJSONArray("result");
        for(int i=0; i<result.size(); i++){
            Object o = result.get(i);
            JSONObject json = JSONObject.parseObject(String.valueOf(o));
            String input = json.getString("input");
            if(input.length() > 10){
                if(input.substring(0,10).equalsIgnoreCase(INPUT_OFFER)){
                    if((blockNumber.subtract(interval)).compareTo(json.getBigInteger("blockNumber")) > 0){
                        log.info("符合要求，开启报价");
                        return true;
                    }else {
//                        log.info("目前还不符合要求, 当前区块高度:" + blockNumber + "  上一个报价的区块高度: " + json.getBigInteger("blockNumber"));
                        return false;
                    }
                }
            }
        }
        return false;
    }
    /**
    * 获取交易所价格
    */
    private static BigDecimal getExchangePrice(){
        String s;
        try {
            s = HttpClientUtil.sendHttpGet(URL_USDT_ETH_PRICE);
        }catch (Exception e){
            return null;
        }
        if(s == null)return null;
        JSONObject jsonObject = JSONObject.parseObject(s);
        BigDecimal price = JSONObject.parseObject(
                String.valueOf(
                        JSONObject.parseObject(
                                String.valueOf(
                                        jsonObject.getJSONArray("data").get(0)
                                )
                        ).getJSONArray("data").get(0)
                )
        ).getBigDecimal("price");
        return price == null ? null : price.setScale(6,BigDecimal.ROUND_DOWN);
    }
    /**
     * 获取需要取回的报价合约地址
     */
    private String getOfferContractAddress() throws Exception {
        String s = HttpClientUtil.sendHttpGet("https://api-cn.etherscan.com/api?module=account&action=txlist&address=0x4F391C202a906EED9e2b63fDd387F28E952782E2&startblock=0&endblock=99999999&page=1&offset=100&sort=desc&apikey=YourApiKeyToken");
        JSONObject jsonObject = JSONObject.parseObject(s);
        JSONArray resultEtherscan = jsonObject.getJSONArray("result");
        Web3j web3j = Web3j.build(new HttpService(NODE));
        Credentials credentials = Credentials.create(USER_PRIVATE_KEY);
        BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
        gasPrice = gasPrice.multiply(new BigInteger("2"));
        for(int i=0; i<resultEtherscan.size(); i++) {
            Object o = resultEtherscan.get(i);
            JSONObject jsonObject1 = JSONObject.parseObject(String.valueOf(o));
            String from = jsonObject1.getString("from");
            String isError = jsonObject1.getString("isError");
            String input = jsonObject1.getString("input");
            String hash = jsonObject1.getString("hash");
            if (input.length() < 10) continue;
            if (input.substring(0, 10).equalsIgnoreCase(INPUT_OFFER) && from.equalsIgnoreCase(credentials.getAddress()) && isError.equalsIgnoreCase("0")) {
                EthGetTransactionReceipt ethGetTransactionReceipt = web3j.ethGetTransactionReceipt(hash).sendAsync().get();
                TransactionReceipt result = ethGetTransactionReceipt.getResult();
                List<Log> logs = result.getLogs();
                if (logs.size() == 0) return null;
                // 遍历当前transactionHash下所有的日志记录
                for (Log log : logs) {
                    List<String> topics = log.getTopics();
                    String address = log.getAddress();
                    if (!address.equalsIgnoreCase(OFFER_FACTORY_CONTRACT)) {
                        continue;        // 确定一定要是报价工厂合约地址,才能继续往下执行
                    }
                    // 如果有报价记录
                    if (topics.get(0).equalsIgnoreCase(TRANSACTION_TOPICS_CONTRACT)) {
                        String data = log.getData();
                        String contractAddress = "0x" + data.substring(26, 66);  // 报价合约地址
                        // 检查是否领取过
                        OfferContract offerContract = OfferContract.load(contractAddress, web3j, credentials, gasPrice, new BigInteger("1000000"));
                        Boolean b = offerContract.checkHadReceive().send();
                        if (!b) { // 如果还未领取
                            return contractAddress;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     *  检测是否进行了一次性授权,如果没有,即进行一次性授权
     */
    private void approveToOfferFactoryContract(Web3j web3j) throws Exception {
        Credentials credentials = Credentials.create(USER_PRIVATE_KEY);
        ERC20 load = ERC20.load(USDT_TOKEN_ADDRESS, web3j, credentials, new BigInteger("10000000000"), new BigInteger("500000"));
        BigInteger approveValue = load.allowance(credentials.getAddress(), OFFER_FACTORY_CONTRACT).send();
        if(approveValue.compareTo(new BigInteger("10000000000")) < 0){
            String transactionHash = load.approve(OFFER_FACTORY_CONTRACT, new BigInteger("99999999999999999999")).send().getTransactionHash();
            System.out.println("一次性授权hash：" + transactionHash);
            Thread.sleep(1000*60*2);
        }
    }

}
