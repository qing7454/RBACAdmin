package com.spring.service.attend.impl;

import com.github.pagehelper.Page;
import com.spring.common.exceptions.MyException;
import com.spring.common.utils.Constants;
import com.spring.common.utils.DateUtils;
import com.spring.common.utils.UUID;
import com.spring.dao.UserMapper;
import com.spring.dao.attend.AttendMapper;
import com.spring.dao.attend.UserAttendMapper;
import com.spring.model.attend.Attend;
import com.spring.model.permission.User;
import com.spring.service.attend.AttendService;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.ibatis.session.RowBounds;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * @Author: Jeremy
 * @Date: 2018/6/6
 * @Time: 16:49
 * @Version: 1.0
 * @Describe:
 */
@Service
@Transactional
public class AttendServiceImpl implements AttendService {

    @Autowired
    private AttendMapper attendMapper;

    @Autowired
    private UserAttendMapper userAttendMapper;

    @Autowired
    private UserMapper userMapper;

    @Override
    public void sign(User user) {
        Date today=new Date();
        //查询当天此人有没有打卡记录
        Attend attend=attendMapper.getTodayRecordAttend(user.getUserId());
        //判定上下午
        Date noon = DateUtils.getDate(Constants.AttendConstants.NOON_HOUR,Constants.AttendConstants.NOON_MINUTE);
        //判定早上是否迟到
        Date morningDate = DateUtils.getDate(Constants.AttendConstants.MORNING_HOUR,Constants.AttendConstants.MORNING_MINUTE);
        //第一次打卡
        if (attend==null){
            //关联表UUID
            attend.setUserAttendId(UUID.getUUID());
            //设置当前考勤数据UUID
            attend.setAttendId(UUID.getUUID());
            //设置当前考勤数据对应的用户信息
            attend.setUserId(user.getUserId());
            //考勤日期
            attend.setAttendDate(DateUtils.getCurrentDay());
            //打卡是星期几
            attend.setAttendWeek(DateUtils.getCurrentWeek());
            //拼装备注信息
            //根据用户Id查询用户名称
            String userName=user.getUserName();
            String currentDay=DateUtils.getCurrentDay();
            attend.setRemark(userName+"-"+currentDay+"打卡记录");
            if (today.compareTo(noon)<=0){
                //上午打卡
                attend.setAttendMorning(today);
                //计算是否早上迟到
                if (today.compareTo(morningDate)>0){
                    attend.setAttendStatus(Constants.AttendConstants.ATTEND_STATUS_ABNORMAL);
                    attend.setAbsence(DateUtils.getMinute(morningDate,today));
                }
            }else {
                attend.setAttendStatus(Constants.AttendConstants.ATTEND_STATUS_ABNORMAL);
                attend.setAbsence(Constants.AttendConstants.ABSENCE_DAY/2);
                attend.setAttendEvening(today);
            }
            //关联插入
            attendMapper.insert(attend);
            //向关联表中插入数据
            userAttendMapper.insert(attend);
        }else {
            if (today.compareTo(noon)>0){
                //下午打卡
                Date eveningDate = DateUtils.getDate(Constants.AttendConstants.EVENING_HOUR,Constants.AttendConstants.EVENING_MINUTE);
                attend.setAttendEvening(today);
                //早退
                int flag=today.compareTo(eveningDate);
                if (flag<0){
                    attend.setAttendStatus(Constants.AttendConstants.ATTEND_STATUS_ABNORMAL);
                    int eveningAbsence=DateUtils.getMinute(today,eveningDate);
                    int morningAbsence=attend.getAbsence();
                    int totalAbsence=morningAbsence+eveningAbsence;
                    attend.setAbsence(totalAbsence);
                }
                //弹性工作制
                if (attend.getAttendMorning()!=null){
                    int officeHours=DateUtils.getMinute(attend.getAttendMorning(),today);
                    if (officeHours>=Constants.AttendConstants.ABSENCE_DAY){
                        attend.setAttendStatus(Constants.AttendConstants.ATTEND_STATUS_NORMAL);
                        attend.setAbsence(null);
                    }
                }
                attendMapper.updateByPrimaryKeySelective(attend);
            }else {
                throw new MyException("请下班之后再重新打卡");
            }
        }
    }

    @Override
    public void checkAttend() {
        /**
         * 第一种情况，上下午都没有打卡，调用自动执行任务，将打卡记录补上，
         * 记为缺勤一整天，状态为异常
         */
        List<String> userIdList = attendMapper.getUserIdAbsence();
        if (CollectionUtils.isNotEmpty(userIdList)){
            List<Attend> list = new ArrayList<>();
            for (String userId : userIdList){
                Attend attend = new Attend();
                attend.setUserId(userId);
                attend.setAttendId(UUID.getUUID());
                attend.setUserAttendId(UUID.getUUID());
                attend.setAttendDate(DateUtils.getCurrentDay());
                attend.setAttendWeek(DateUtils.getCurrentWeek());
                //拼装备注信息
                //根据用户Id查询用户名称
                String userName=userMapper.getUserByUserId(userId).getUserName();
                String currentDay=DateUtils.getCurrentDay();
                attend.setRemark(userName+"-"+currentDay+"打卡记录");
                attend.setAbsence(Constants.AttendConstants.ABSENCE_DAY);
                attend.setAttendStatus(Constants.AttendConstants.ATTEND_STATUS_ABNORMAL);
                list.add(attend);
            }
            //打卡记录表批量插入
            attendMapper.batchInsert(list);
            //关联记录表批量插入
            userAttendMapper.batchInsert(list);
        }
        /**
         * 第二种情况：上午完成了打卡，但是晚上忘记了打卡
         * 同样将缺勤时间记为240分钟，状态记为异常
         */
        List<Attend> attendList=attendMapper.getTodayEveningAbsence();
        //将状态置为0，缺勤240分钟
        if (CollectionUtils.isNotEmpty(attendList)){
            for (Attend attend:attendList){
                attend.setAttendStatus(Constants.AttendConstants.ATTEND_STATUS_ABNORMAL);
                attend.setAbsence(attend.getAbsence()+Constants.AttendConstants.ABSENCE_DAY/2);
                attendMapper.updateByPrimaryKeySelective(attend);
            }
        }
    }

    @Override
    public List<Attend> getAllAttendRecord(String userId,String id) {

        List<Attend> list1=null ;
        if (id.equals("lastmonth")){
            list1=attendMapper.getLastMonthAttendRecord(userId);
        }else if (id.equals("week")){
            list1=attendMapper.getCurrentWeekAttendRecord(userId);
        }else {
            list1=attendMapper.getCurrentMonthAttendRecord(userId);
        }
         List<Attend> list=new ArrayList<>();
         for (Attend attend: list1){
             Date startTime = attend.getAttendMorning();
             Date endTime = attend.getAttendEvening();
             attend.setWorkHours(DateUtils.getWorkHours(startTime,endTime));
             list.add(attend);
         }
         return list;

    }
}