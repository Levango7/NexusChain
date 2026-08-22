/*
 * Copyright (c) [2018]
 * This file is part of the java-nexuscore
 *
 * The java-nexuscore is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The java-nexuscore is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with the java-nexuscore. If not, see <http://www.gnu.org/licenses/>.
 */

package org.nexus.controller;

import org.apache.commons.codec.binary.Hex;
import org.nexus.ApiResult.APIResult;
import org.nexus.keystore.wallet.KeystoreAction;
import org.nexus.service.HatchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
public class HatchController {

    @Autowired
    HatchService hatchService;

    @RequestMapping(value="/sendBalance",method = RequestMethod.POST)
    public Object sendBalance(@RequestParam("pubkeyhash") String pubkeyhash){
        return hatchService.getBalance(pubkeyhash);
    }

    @RequestMapping(value="/getAddressBalance",method = RequestMethod.POST)
    public Object getAddressBalance(@RequestParam("address") String address){
        try{
            byte[] pubhash=KeystoreAction.addressToPubkeyHash(address);
            String pubkeyhash= Hex.encodeHexString(pubhash);
            return hatchService.getBalance(pubkeyhash);
        }catch (RuntimeException e){
            return APIResult.newFailResult(5000,"ERROR");
        }

    }

    @RequestMapping(value="/getTxrecordFromAddress",method =RequestMethod.GET )
    public Object getTxrecordFromAddress(@RequestParam("address") String address){
        return hatchService.getTxrecordFromAddress(address);
    }

    @RequestMapping(value="/sendNonce",method =RequestMethod.POST )
    public Object sendNonce(@RequestParam("pubkeyhash") String pubkeyhash){
        return hatchService.getNonce(pubkeyhash);
    }

    @RequestMapping(value="/NexusChainCore/getNowInterest",method = RequestMethod.POST)
    public Object getNowInterest(@RequestParam("coinHash") String coinHash){
        return hatchService.getNowInterest(coinHash);
    }

    @RequestMapping(value="/NexusChainCore/getNowShare",method = RequestMethod.POST)
    public Object getNowShare(@RequestParam("coinHash") String coinHash){
        return hatchService.getNowShare(coinHash);
    }

    @GetMapping(value = "/NexusChainCore/sendTransferList")
    public Object sendTransferList(@RequestParam("height") int height) {
        return hatchService.getTransfer(height);
    }

    @GetMapping(value ="/NexusChainCore/sendHatchList")
    public Object sendHatchList(@RequestParam("height") int height){
        return hatchService.getHatch(height);
    }

    @GetMapping(value = "/NexusChainCore/sendInterestList")
    public Object sendInterestList(@RequestParam("height") int height){
        return hatchService.getInterest(height);
    }

    @GetMapping(value = "/NexusChainCore/sendShareList")
    public Object sendShareList(@RequestParam("height") int height){
        return hatchService.getShare(height);
    }

    @GetMapping(value = "/NexusChainCore/sendCostList")
    public Object sendCostList(@RequestParam("height") int height){
        return hatchService.getCost(height);
    }

    @GetMapping(value = "/NexusChainCore/sendVoteList")
    public Object sendVoteList(@RequestParam("height") int height){
        return hatchService.getVote(height);
    }

    @GetMapping(value = "/NexusChainCore/sendCancelVoteList")
    public Object sendCancelVoteList(@RequestParam("height") int height){
        return hatchService.getCancelVote(height);
    }

    @GetMapping(value = "/NexusChainCore/sendMortgageList")
    public Object sendMortgageList(@RequestParam("height") int height){
        return hatchService.getMortgage(height);
    }

    @GetMapping(value = "/NexusChainCore/sendCancelMortgageList")
    public Object sendCancelMortgageList(@RequestParam("height") int height){
        return hatchService.getCancelMortgage(height);
    }

}