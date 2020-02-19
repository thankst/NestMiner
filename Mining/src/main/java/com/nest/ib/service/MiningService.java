package com.nest.ib.service;

import org.web3j.protocol.Web3j;

import java.math.BigDecimal;

/**
 * ClassName:MiningService
 * Description:
 */
public interface MiningService {
    void offer();

    void sendUSDTOffer(BigDecimal ethAmount,Web3j web3j) throws Exception;

    void turnOut() throws Exception;

    void updateIntervalBlock(int blockInterval);

    int selectIntervalBlock();

    void startMining();

    boolean selectStartMining();

    void updateUserPrivateKey(String privateKey);

    String selectUserWalletAddress();

    void removeApprove() throws Exception;
}
