/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.desktop.main.dao.economy.supply.chart;

import bisq.desktop.components.chart.ChartModel;
import bisq.desktop.main.dao.economy.supply.DaoEconomyDataProvider;

import bisq.core.dao.state.DaoStateService;

import bisq.common.util.Tuple2;

import javax.inject.Inject;

import javafx.scene.chart.XYChart;

import java.time.Instant;
import java.time.temporal.TemporalAdjuster;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DaoEconomyChartModel extends ChartModel {
    private final DaoStateService daoStateService;
    private final DaoEconomyDataProvider daoEconomyDataProvider;

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public DaoEconomyChartModel(DaoStateService daoStateService, DaoEconomyDataProvider daoEconomyDataProvider) {
        super();

        this.daoStateService = daoStateService;
        this.daoEconomyDataProvider = daoEconomyDataProvider;
    }

    @Override
    protected void applyTemporalAdjuster(TemporalAdjuster temporalAdjuster) {
        daoEconomyDataProvider.setTemporalAdjuster(temporalAdjuster);
    }

    @Override
    protected TemporalAdjuster getTemporalAdjuster() {
        return daoEconomyDataProvider.getTemporalAdjuster();
    }

    List<XYChart.Data<Number, Number>> getBsqTradeFeeChartData(Predicate<Long> predicate) {
        return toChartData(daoEconomyDataProvider.getBurnedBsqByMonth(daoStateService.getTradeFeeTxs(), predicate));
    }

    List<XYChart.Data<Number, Number>> getCompensationChartData(Predicate<Long> predicate) {
        return toChartData(daoEconomyDataProvider.getMergedCompensationMap(predicate));
    }

    List<XYChart.Data<Number, Number>> getProofOfBurnChartData(Predicate<Long> predicate) {
        return toChartData(daoEconomyDataProvider.getBurnedBsqByMonth(daoStateService.getProofOfBurnTxs(), predicate));
    }

    List<XYChart.Data<Number, Number>> getReimbursementChartData(Predicate<Long> predicate) {
        return toChartData(daoEconomyDataProvider.getMergedReimbursementMap(predicate));
    }

    List<XYChart.Data<Number, Number>> getTotalIssuedChartData(Predicate<Long> predicate) {
        Map<Long, Long> compensationMap = daoEconomyDataProvider.getMergedCompensationMap(predicate);
        Map<Long, Long> reimbursementMap = daoEconomyDataProvider.getMergedReimbursementMap(predicate);
        Map<Long, Long> sum = daoEconomyDataProvider.getMergedMap(compensationMap, reimbursementMap, Long::sum);
        return toChartData(sum);
    }

    List<XYChart.Data<Number, Number>> getTotalBurnedChartData(Predicate<Long> predicate) {
        Map<Long, Long> tradeFee = daoEconomyDataProvider.getBurnedBsqByMonth(daoStateService.getTradeFeeTxs(), predicate);
        Map<Long, Long> proofOfBurn = daoEconomyDataProvider.getBurnedBsqByMonth(daoStateService.getProofOfBurnTxs(), predicate);
        Map<Long, Long> sum = daoEconomyDataProvider.getMergedMap(tradeFee, proofOfBurn, Long::sum);
        return toChartData(sum);
    }

    void initBounds(List<XYChart.Data<Number, Number>> tradeFeeChartData,
                    List<XYChart.Data<Number, Number>> compensationRequestsChartData) {
        Tuple2<Double, Double> xMinMaxTradeFee = getMinMax(tradeFeeChartData);
        Tuple2<Double, Double> xMinMaxCompensationRequest = getMinMax(compensationRequestsChartData);

        lowerBound = Math.min(xMinMaxTradeFee.first, xMinMaxCompensationRequest.first);
        upperBound = Math.max(xMinMaxTradeFee.second, xMinMaxCompensationRequest.second);
    }

    long toTimeInterval(Instant ofEpochSecond) {
        return daoEconomyDataProvider.toTimeInterval(ofEpochSecond);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Utils
    ///////////////////////////////////////////////////////////////////////////////////////////

    private static List<XYChart.Data<Number, Number>> toChartData(Map<Long, Long> map) {
        return map.entrySet().stream()
                .map(entry -> new XYChart.Data<Number, Number>(entry.getKey(), entry.getValue()))
                .collect(Collectors.toList());
    }

    private static Tuple2<Double, Double> getMinMax(List<XYChart.Data<Number, Number>> chartData) {
        long min = Long.MAX_VALUE, max = 0;
        for (XYChart.Data<Number, ?> data : chartData) {
            min = Math.min(data.getXValue().longValue(), min);
            max = Math.max(data.getXValue().longValue(), max);
        }
        return new Tuple2<>((double) min, (double) max);
    }
}
