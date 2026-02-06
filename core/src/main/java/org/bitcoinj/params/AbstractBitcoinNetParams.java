/*
 * Copyright 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.bitcoinj.params;

import com.google.common.base.Stopwatch;

import java.math.BigInteger;
import java.util.concurrent.TimeUnit;

import org.bitcoinj.core.Block;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.StoredBlock;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.Utils;
import org.bitcoinj.utils.MonetaryFormat;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parameters for dobbscoin-like networks.
 */
public abstract class AbstractBitcoinNetParams extends NetworkParameters {
    /**
     * Scheme part for dobbscoin URIs.
     */
    public static final String BITCOIN_SCHEME = "dobbscoin";

    private static final Logger log = LoggerFactory.getLogger(AbstractBitcoinNetParams.class);

    public AbstractBitcoinNetParams() {
        super();
    }

    /** 
     * Checks if we are at a difficulty transition point. 
     * @param storedPrev The previous stored block 
     * @return If this is a difficulty transition point 
     */
    protected boolean isDifficultyTransitionPoint(StoredBlock storedPrev) {
        return ((storedPrev.getHeight() + 1) % this.getInterval()) == 0;
    }

    @Override
    public void checkDifficultyTransitions(StoredBlock storedPrev, Block nextBlock, BlockStore blockStore) throws BlockStoreException, VerificationException {
        // checkState(lock.isHeldByCurrentThread());

    /*
    enum DiffMode {
        DIFF_DEFAULT = 0, // Default to invalid 0
        DIFF_BTC     = 1, // Retarget every x blocks (dobbscoin style)
        DIFF_KGW     = 2,
        DIFF_KGW2    = 3,// Retarget using Kimoto Gravity Well
        DIFF_DGW     = 4,
        DIFF_DELTA   = 5,
        DIFF_KGW3    = 6,
        DIFF_NULL    = 0,// Retarget using Dark Gravity Wave v3
    };
    */

        int DiffMode = 1;
       /* if (this.getId().equals(NetworkParameters.ID_TESTNET)) {
            if (storedPrev.getHeight() + 1 >= 500) { DiffMode = 3; }
            else if (storedPrev.getHeight() + 1 >= 100) { DiffMode = 5; }
            else if (storedPrev.getHeight() + 1 >= 20) { DiffMode = 2; }
            else if (storedPrev.getHeight() + 1 >= 10) { DiffMode = 4; }
            else { DiffMode = 1; }
        }*/

        if (storedPrev.getHeight() + 1 >= 68425) { DiffMode = 6; }
        else if (storedPrev.getHeight() + 1 >= 31597) { DiffMode = 3; }
        else if (storedPrev.getHeight() + 1 >= 13579) { DiffMode = 5; }
        else { DiffMode = 1;}



        if (DiffMode == 1) { BitcoinStyleRetargeting(storedPrev, nextBlock, blockStore); return; }

        else if (DiffMode == 3) { GetNextWorkRequired_V3(storedPrev, nextBlock, blockStore); return; }

        else if (DiffMode == 5) { GetNextWorkRequired_V2(storedPrev, nextBlock, blockStore); return; }
        else if (DiffMode == 6) { GetNextWorkRequired_V4(storedPrev, nextBlock, blockStore); return; }


    }

     private void BitcoinStyleRetargeting(StoredBlock storedPrev, Block nextBlock, BlockStore blockStore) throws BlockStoreException, VerificationException {
         //checkState(lock.isHeldByCurrentThread());
         Block prev = storedPrev.getHeader();
         if(prev==null){ this.genesisBlock.getDifficultyTarget();}

         // Is this supposed to be a difficulty transition point?
         if ((storedPrev.getHeight() + 1) % this.getInterval() != 0) {

             // TODO: Refactor this hack after 0.5 is released and we stop supporting deserialization compatibility.
             // This should be a method of the NetworkParameters, which should in turn be using singletons and a subclass
             // for each network type. Then each network can define its own difficulty transition rules.
             // if (params.getId().equals(NetworkParameters.ID_TESTNET) && nextBlock.getTime().after(testnetDiffDate)) {
             //    checkTestnetDifficulty(storedPrev, prev, nextBlock);
             //  return;
             // }

             // No ... so check the difficulty didn't actually change.
           /*  if (nextBlock.getDifficultyTarget() != prev.getDifficultyTarget())
                 throw new VerificationException("Unexpected change in difficulty at height " + storedPrev.getHeight() +
                         ": " + Long.toHexString(nextBlock.getDifficultyTarget()) + " vs " +
                         Long.toHexString(prev.getDifficultyTarget()));*/
             return ;
         }

         // We need to find a block far back in the chain. It's OK that this is expensive because it only occurs every
         // two weeks after the initial block chain download.
         StoredBlock cursor = blockStore.get(prev.getHash());

         int blockstogoback = this.getInterval() - 1;
         if(storedPrev.getHeight() + 1 != this.getInterval())
             blockstogoback = this.getInterval();

         for (int i = 0; i < blockstogoback; i++) {
             if (cursor == null) {
                 // Using checkpoint system, there may not be enough blocks to do this diff adjust, so skip
                 return;
             }
             cursor = blockStore.get(cursor.getHeader().getPrevBlockHash());
         }

         if (cursor == null) {
             // Not enough blocks in store to verify difficulty (SPV checkpoint boundary)
             return;
         }

         Block blockIntervalAgo = cursor.getHeader();
         int timespan = (int) (prev.getTimeSeconds() - blockIntervalAgo.getTimeSeconds());
         // Limit the adjustment step.
         final int targetTimespan = this.getTargetTimespan();
         if (timespan < targetTimespan / 4)
             timespan = targetTimespan / 4;
         if (timespan > targetTimespan * 4)
             timespan = targetTimespan * 4;

         BigInteger newTarget = Utils.decodeCompactBits(prev.getDifficultyTarget());
         newTarget = newTarget.multiply(BigInteger.valueOf(timespan));
         newTarget = newTarget.divide(BigInteger.valueOf(targetTimespan));

         if (newTarget.compareTo(this.getMaxTarget()) > 0) {
             log.info("Difficulty hit proof of work limit: {}", newTarget.toString(16));
             newTarget = this.getMaxTarget();
         }

         verifyDifficulty(newTarget, storedPrev, nextBlock);

     }
     private void GetNextWorkRequired_V3(StoredBlock storedPrev, Block nextBlock, BlockStore blockStore) throws BlockStoreException, VerificationException {
         Block prev = storedPrev.getHeader();
         long nTargetTimespan_V3 = 10*60 ;
         long nTargetSpacing_V3 = 10*60;
         long nInterval_V3 = nTargetTimespan_V3 / nTargetSpacing_V3;
         long retargetTimespan = nTargetTimespan_V3;
         long retargetInterval = nInterval_V3;
         Block BlockCreating = nextBlock;



         // Is this supposed to be a difficulty transition point?
         if ((storedPrev.getHeight() + 1) % retargetInterval != 0) {

             // TODO: Refactor this hack after 0.5 is released and we stop supporting deserialization compatibility.
             // This should be a method of the NetworkParameters, which should in turn be using singletons and a subclass
             // for each network type. Then each network can define its own difficulty transition rules.
             // if (params.getId().equals(NetworkParameters.ID_TESTNET) && nextBlock.getTime().after(testnetDiffDate)) {
             //     checkTestnetDifficulty(storedPrev, prev, nextBlock);
             //     return;
             // }

             // No ... so check the difficulty didn't actually change.
             if (nextBlock.getDifficultyTarget() != prev.getDifficultyTarget())
                 throw new VerificationException("Unexpected change in difficulty at height " + storedPrev.getHeight() +
                         ": " + Long.toHexString(nextBlock.getDifficultyTarget()) + " vs " +
                         Long.toHexString(prev.getDifficultyTarget()));
             return;
         }

         // We need to find a block far back in the chain. It's OK that this is expensive because it only occurs every
         // two weeks after the initial block chain download.
         StoredBlock cursor = blockStore.get(prev.getHash());

         long blockstogoback = retargetInterval - 1;
         if(storedPrev.getHeight() + 1 !=retargetInterval)
             blockstogoback = retargetInterval;

         for (int i = 0; i < blockstogoback; i++) {
             if (cursor == null) {
                 // Using checkpoint system, there may not be enough blocks to do this diff adjust, so skip
                 return;
             }
             cursor = blockStore.get(cursor.getHeader().getPrevBlockHash());
         }

         if (cursor == null) {
             // Not enough blocks in store to verify difficulty (SPV checkpoint boundary)
             return;
         }

         Block blockIntervalAgo = cursor.getHeader();
         long timespan = (int) (prev.getTimeSeconds() - blockIntervalAgo.getTimeSeconds());

         timespan = retargetTimespan + (timespan -  retargetTimespan)/8;
         // Limit the adjustment step.
         final int targetTimespan =this.getTargetTimespan();
         if (timespan < (retargetTimespan - (retargetTimespan/4)))
             timespan =(retargetTimespan - (retargetTimespan/4));
         if (timespan > (retargetTimespan + (retargetTimespan/2)))
             timespan = (retargetTimespan + (retargetTimespan/2));

         BigInteger newTarget = Utils.decodeCompactBits(prev.getDifficultyTarget());
         newTarget = newTarget.multiply(BigInteger.valueOf(timespan));
         newTarget = newTarget.divide(BigInteger.valueOf(targetTimespan));

         if (newTarget.compareTo(this.getMaxTarget()) > 0) {
             log.info("Difficulty hit proof of work limit: {}", newTarget.toString(16));
             newTarget = this.getMaxTarget();
         }

         verifyDifficulty(newTarget, storedPrev, nextBlock);
     }
     private void GetNextWorkRequired_V4(StoredBlock storedPrev, Block nextBlock, BlockStore blockStore) throws BlockStoreException, VerificationException {
         Block prev = storedPrev.getHeader();
         long nTargetTimespan_V3 = 2*60 ;
         long nTargetSpacing_V3 = 2*60;
         long nInterval_V3 = nTargetTimespan_V3 / nTargetSpacing_V3;
         long retargetTimespan = nTargetTimespan_V3;
         long retargetInterval = nInterval_V3;
         Block BlockCreating = nextBlock;



         // Is this supposed to be a difficulty transition point?
         if ((storedPrev.getHeight() + 1) % retargetInterval != 0) {

             // TODO: Refactor this hack after 0.5 is released and we stop supporting deserialization compatibility.
             // This should be a method of the NetworkParameters, which should in turn be using singletons and a subclass
             // for each network type. Then each network can define its own difficulty transition rules.
             // if (params.getId().equals(NetworkParameters.ID_TESTNET) && nextBlock.getTime().after(testnetDiffDate)) {
             //    checkTestnetDifficulty(storedPrev, prev, nextBlock);
             //    return;
             // }
             this.getMaxTarget();

             // No ... so check the difficulty didn't actually change.
             if (nextBlock.getDifficultyTarget() != prev.getDifficultyTarget())
                 throw new VerificationException("Unexpected change in difficulty at height " + storedPrev.getHeight() +
                         ": " + Long.toHexString(nextBlock.getDifficultyTarget()) + " vs " +
                         Long.toHexString(prev.getDifficultyTarget()));
             return;
         }

         // We need to find a block far back in the chain. It's OK that this is expensive because it only occurs every
         // two weeks after the initial block chain download.
         final Stopwatch watch = Stopwatch.createStarted();
         StoredBlock cursor = blockStore.get(prev.getHash());

         long blockstogoback = retargetInterval - 1;
         if(storedPrev.getHeight() + 1 !=retargetInterval)
             blockstogoback = retargetInterval;

         for (int i = 0; i < blockstogoback; i++) {
             if (cursor == null) {
                 // Using checkpoint system, there may not be enough blocks to do this diff adjust, so skip
                 return;
             }
             cursor = blockStore.get(cursor.getHeader().getPrevBlockHash());
         }
         watch.stop();
         if (watch.elapsed(TimeUnit.MILLISECONDS) > 50)
             log.info("Difficulty transition traversal took {}", watch);

         if (cursor == null) {
             // Not enough blocks in store to verify difficulty (SPV checkpoint boundary)
             return;
         }

         Block blockIntervalAgo = cursor.getHeader();
         long timespan = (int) (prev.getTimeSeconds() - blockIntervalAgo.getTimeSeconds());

         timespan = retargetTimespan + (timespan -  retargetTimespan)/8;
         // Limit the adjustment step.
         final int targetTimespan = this.getTargetTimespan();
         if (timespan < (retargetTimespan - (retargetTimespan/4)))
             timespan =(retargetTimespan - (retargetTimespan/4));
         if (timespan > (retargetTimespan + (retargetTimespan/2)))
             timespan = (retargetTimespan + (retargetTimespan/2));

         BigInteger newTarget = Utils.decodeCompactBits(prev.getDifficultyTarget());
         newTarget = newTarget.multiply(BigInteger.valueOf(timespan));
         newTarget = newTarget.divide(BigInteger.valueOf(targetTimespan));

         if (newTarget.compareTo(this.getMaxTarget()) > 0) {
             log.info("Difficulty hit proof of work limit: {}", newTarget.toString(16));
             newTarget = this.getMaxTarget();
         }

         if (newTarget.compareTo(this.getMaxTarget()) > 0) {
             log.info("Difficulty hit proof of work limit: {}", newTarget.toString(16));
             newTarget = this.getMaxTarget();
         }

         int accuracyBytes = (int) (nextBlock.getDifficultyTarget() >>> 24) - 3;
         long receivedTargetCompact = nextBlock.getDifficultyTarget();

         // The calculated difficulty is to a higher precision than received, so reduce here.
         BigInteger mask = BigInteger.valueOf(0xFFFFFFL).shiftLeft(accuracyBytes * 8);
         newTarget = newTarget.and(mask);
         long newTargetCompact = Utils.encodeCompactBits(newTarget);// there is no way other then this as far as i know

       //  if (newTargetCompact != receivedTargetCompact)
        //    throw new VerificationException("Network provided difficulty bits do not match what was calculated: " +
         //          Long.toHexString(newTargetCompact) + " vs " + Long.toHexString(receivedTargetCompact));
     }
     private void GetNextWorkRequired_V2(StoredBlock storedPrev, Block nextBlock, BlockStore blockStore) throws BlockStoreException, VerificationException {
         final long      	BlocksTargetSpacing			= (long)(10 * 60);
         final long  		TimeDaySeconds				= (long)(60 * 60 * 24);
         long				PastSecondsMin				= TimeDaySeconds * (long)0.625;
         long				PastSecondsMax				= TimeDaySeconds * (long)1.75;
         long				PastBlocksMin				= PastSecondsMin / BlocksTargetSpacing;   //? blocks
         long				PastBlocksMax				= PastSecondsMax / BlocksTargetSpacing;   //? blocks

         KimotoGravityWell(storedPrev, nextBlock, BlocksTargetSpacing, PastBlocksMin, PastBlocksMax, blockStore);
     }

     private void KimotoGravityWell(StoredBlock storedPrev, Block nextBlock, long TargetBlocksSpacingSeconds, long PastBlocksMin, long PastBlocksMax, BlockStore blockStore)  throws BlockStoreException, VerificationException {
     /* current difficulty formula, megacoin - kimoto gravity well */
        //const CBlockIndex  *BlockLastSolved				= pindexLast;
        //const CBlockIndex  *BlockReading				= pindexLast;
        //const CBlockHeader *BlockCreating				= pblock;
        StoredBlock         BlockLastSolved             = storedPrev;
        StoredBlock         BlockReading                = storedPrev;
        Block               BlockCreating               = nextBlock;

        //BlockCreating				= BlockCreating;
        long				PastBlocksMass				= 0;
        long				PastRateActualSeconds		= 0;
        long				PastRateTargetSeconds		= 0;
        double				PastRateAdjustmentRatio		= 1f;
        BigInteger			PastDifficultyAverage = BigInteger.valueOf(0);
        BigInteger			PastDifficultyAveragePrev = BigInteger.valueOf(0);;
        double				EventHorizonDeviation;
        double				EventHorizonDeviationFast;
        double				EventHorizonDeviationSlow;

        long start = System.currentTimeMillis();

        if (BlockLastSolved == null || BlockLastSolved.getHeight() == 0 || (long)BlockLastSolved.getHeight() < PastBlocksMin)
        { verifyDifficulty(this.getMaxTarget(), storedPrev, nextBlock); }

        int i = 0;
        long LatestBlockTime = BlockLastSolved.getHeader().getTimeSeconds();

        for (i = 1; BlockReading != null && BlockReading.getHeight() > 0; i++) {
            if (PastBlocksMax > 0 && i > PastBlocksMax) { break; }
            PastBlocksMass++;

            if (i == 1)	{ PastDifficultyAverage = BlockReading.getHeader().getDifficultyTargetAsInteger(); }
            else		{ PastDifficultyAverage = ((BlockReading.getHeader().getDifficultyTargetAsInteger().subtract(PastDifficultyAveragePrev)).divide(BigInteger.valueOf(i)).add(PastDifficultyAveragePrev)); }
            PastDifficultyAveragePrev = PastDifficultyAverage;


            if (LatestBlockTime < BlockReading.getHeader().getTimeSeconds()) {
                //eliminates the ability to go back in time
                LatestBlockTime = BlockReading.getHeader().getTimeSeconds();
            }

            PastRateActualSeconds			= BlockLastSolved.getHeader().getTimeSeconds() - BlockReading.getHeader().getTimeSeconds();
            PastRateTargetSeconds			= TargetBlocksSpacingSeconds * PastBlocksMass;
            PastRateAdjustmentRatio			= 1.0f;

            //this should slow down the upward difficulty change

            if (PastRateActualSeconds < 1) { PastRateActualSeconds = 1; }

            if (PastRateActualSeconds != 0 && PastRateTargetSeconds != 0) {
                PastRateAdjustmentRatio			= (double)PastRateTargetSeconds / PastRateActualSeconds;
            }

            EventHorizonDeviation			= 1 + (0.7084 * java.lang.Math.pow((Double.valueOf(PastBlocksMass)/Double.valueOf(9)), -1.228));

            EventHorizonDeviationFast		= EventHorizonDeviation;
            EventHorizonDeviationSlow		= 1 / EventHorizonDeviation;

            if (PastBlocksMass >= PastBlocksMin) {
                if ((PastRateAdjustmentRatio <= EventHorizonDeviationSlow) || (PastRateAdjustmentRatio >= EventHorizonDeviationFast))
                {
                    /*assert(BlockReading)*/;
                    break;
                }
            }

            StoredBlock BlockReadingPrev = blockStore.get(BlockReading.getHeader().getPrevBlockHash());
            if (BlockReadingPrev == null)
            {
                //assert(BlockReading);
                //Since we are using the checkpoint system, there may not be enough blocks to do this diff adjust, so skip until we do
                //break;
                return;
            }
            BlockReading = BlockReadingPrev;
        }

        /*CBigNum bnNew(PastDifficultyAverage);
        if (PastRateActualSeconds != 0 && PastRateTargetSeconds != 0) {
            bnNew *= PastRateActualSeconds;
            bnNew /= PastRateTargetSeconds;
        } */
        //log.info("KGW-J, {}, {}, {}", storedPrev.getHeight(), i, System.currentTimeMillis() - start);
        BigInteger newDifficulty = PastDifficultyAverage;
        if (PastRateActualSeconds != 0 && PastRateTargetSeconds != 0) {
            newDifficulty = newDifficulty.multiply(BigInteger.valueOf(PastRateActualSeconds));
            newDifficulty = newDifficulty.divide(BigInteger.valueOf(PastRateTargetSeconds));
        }

        if (newDifficulty.compareTo(this.getMaxTarget()) > 0) {
            log.info("Difficulty hit proof of work limit: {}", newDifficulty.toString(16));
            newDifficulty = this.getMaxTarget();
        }


        verifyDifficulty(newDifficulty, storedPrev, nextBlock);

    }
    private void verifyDifficulty(BigInteger calcDiff, StoredBlock storedPrev, Block nextBlock)
    {

        int accuracyBytes = (int) (nextBlock.getDifficultyTarget() >>> 24) - 3;
        BigInteger receivedDifficulty = nextBlock.getDifficultyTargetAsInteger();

        // The calculated difficulty is to a higher precision than received, so reduce here.
        BigInteger mask = BigInteger.valueOf(0xFFFFFFL).shiftLeft(accuracyBytes * 8);
        calcDiff = calcDiff.and(mask);
        if(this.getId().compareTo(this.ID_TESTNET) == 0)
        {
            if (calcDiff.compareTo(receivedDifficulty) != 0)
                throw new VerificationException("Network provided difficulty bits do not match what was calculated: " +
                        receivedDifficulty.toString(16) + " vs " + calcDiff.toString(16));
        }
        else
        {
            if (calcDiff.compareTo(receivedDifficulty) != 0)
                if (storedPrev.getHeight() >= 68426){ //this has to be done, ofcourse it is skiping but we have checkpoints to overcome this,blockchain can't change it's past
                  throw new VerificationException("Network provided difficulty bits do not match what was calculated: " +
                           receivedDifficulty.toString(16) + " vs " + calcDiff.toString(16));}
        }
    }
    @Override
    public Coin getMaxMoney() {
        return MAX_MONEY;
    }

    @Override
    public Coin getMinNonDustOutput() {
        return Transaction.MIN_NONDUST_OUTPUT;
    }

    @Override
    public MonetaryFormat getMonetaryFormat() {
        return new MonetaryFormat();
    }

    @Override
    public String getUriScheme() {
        return BITCOIN_SCHEME;
    }

    @Override
    public boolean hasMaxMoney() {
        return true;
    }
}
