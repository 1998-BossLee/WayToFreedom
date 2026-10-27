package yangjin.li.kline;

/** MA5 与 MA10 的位置和交叉事件接口常量；当前位置不等于本根发生交叉。 */
public interface MaCrossState {

    /** 当前收盘样本中 MA5 相对于 MA10 的位置。 */
    interface Position {
        String ABOVE = "MA5高于MA10";
        String BELOW = "MA5低于MA10";
        String EQUAL = "重合";
        String UNKNOWN = "未知";
    }

    /** 相对位置发生穿越时才产生事件；初始位置和单纯触碰均不产生交叉。 */
    interface Event {
        String GOLDEN = "金叉";
        String DEAD = "死叉";
        String NONE = "无";
        String UNKNOWN = "未知";
    }

    /** 趋平方向由进入前连续三根变化率的方向决定，与当前变化率的正负无关。 */
    interface FlatDirection {
        String UP = "上涨趋平";
        String DOWN = "下跌趋平";
    }
}
