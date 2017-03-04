package org.sa.rainbow.brass.analyses;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.sa.rainbow.brass.model.instructions.ForwardInstruction;
import org.sa.rainbow.brass.model.instructions.IInstruction;
import org.sa.rainbow.brass.model.instructions.InstructionGraphModelInstance;
import org.sa.rainbow.brass.model.instructions.InstructionGraphProgress;
import org.sa.rainbow.brass.model.instructions.MoveAbsHInstruction;
import org.sa.rainbow.brass.model.map.BatteryPredictor;
import org.sa.rainbow.brass.model.map.EnvMap;
import org.sa.rainbow.brass.model.map.EnvMapModelInstance;
import org.sa.rainbow.brass.model.map.MapTranslator;
import org.sa.rainbow.brass.model.mission.MissionState;
import org.sa.rainbow.brass.model.mission.MissionState.Heading;
import org.sa.rainbow.brass.model.mission.MissionStateModelInstance;
import org.sa.rainbow.brass.model.mission.SetRobotAccurateCmd;
import org.sa.rainbow.core.AbstractRainbowRunnable;
import org.sa.rainbow.core.IRainbowRunnable;
import org.sa.rainbow.core.Rainbow;
import org.sa.rainbow.core.RainbowComponentT;
import org.sa.rainbow.core.RainbowConstants;
import org.sa.rainbow.core.analysis.IRainbowAnalysis;
import org.sa.rainbow.core.error.RainbowConnectionException;
import org.sa.rainbow.core.event.IRainbowMessage;
import org.sa.rainbow.core.models.ModelReference;
import org.sa.rainbow.core.ports.IModelChangeBusPort;
import org.sa.rainbow.core.ports.IModelChangeBusSubscriberPort;
import org.sa.rainbow.core.ports.IModelUSBusPort;
import org.sa.rainbow.core.ports.IModelsManagerPort;
import org.sa.rainbow.core.ports.IRainbowReportingPort;
import org.sa.rainbow.core.ports.RainbowPortFactory;
import org.sa.rainbow.core.util.Pair;
import org.sa.rainbow.core.ports.IModelChangeBusSubscriberPort.IRainbowChangeBusSubscription;
import org.sa.rainbow.core.ports.IModelChangeBusSubscriberPort.IRainbowModelChangeCallback;

/**
 * Analyzes accuracy (distance-to-goal) property of the current plan, and triggers adaptation if necessary.
 * @author rsukkerd
 *
 */
public class AccuracyAnalyzer extends AbstractRainbowRunnable implements IRainbowAnalysis, IRainbowModelChangeCallback {
	
	public static final String NAME = "BRASS Accuracy Evaluator";
	
	private static final int GOAL_RADIUS = 50; // centimeters //TODO
    
    private IModelChangeBusSubscriberPort		m_modelChangePort;
    private IModelsManagerPort					m_modelsManagerPort;
    private IModelUSBusPort						m_modelUSPort;
    
    private IRainbowChangeBusSubscription m_plannerFinish = new IRainbowChangeBusSubscription() {
		
		@Override
		public boolean matches(IRainbowMessage message) {
			String modelName = (String) message.getProperty (
                    IModelChangeBusPort.MODEL_NAME_PROP);
            String modelType = (String) message.getProperty (
                    IModelChangeBusPort.MODEL_TYPE_PROP);
            String commandName = (String) message.getProperty (
                    IModelChangeBusPort.COMMAND_PROP);
            
            // New IG event
            boolean isNewIGEvent = InstructionGraphModelInstance.INSTRUCTION_GRAPH_TYPE.equals(modelType) 
            		&& "ExecutingInstructionGraph".equals (modelName)
            		&& "setInstructions".equals (commandName);
            
            return isNewIGEvent;
		}
	};
	
	// If adaptation planning is in progress, this analyzer will wait for it to finish
	private boolean m_waitForPlanner = false;
	
	// Keep track of the previously-analyzed instruction, which passed
    private IInstruction m_prevAnalyzedAndPassedInstruction;

    private ModelReference m_igRef = new ModelReference("ExecutingInstructionGraph", InstructionGraphModelInstance.INSTRUCTION_GRAPH_TYPE);
    private ModelReference m_msRef = new ModelReference("RobotAndEnvironmentState", MissionStateModelInstance.MISSION_STATE_TYPE);
    private ModelReference m_emRef = new ModelReference ("Map", EnvMapModelInstance.ENV_MAP_TYPE);

    private InstructionGraphProgress m_igProgress;
    private MissionState m_missionState;
    private EnvMap m_envMap;
	
	public AccuracyAnalyzer() {
		super(NAME);
		String period = Rainbow.instance ().getProperty (RainbowConstants.PROPKEY_MODEL_EVAL_PERIOD);
        if (period != null) {
            setSleepTime (Long.parseLong (period));
        } else {
            setSleepTime (IRainbowRunnable.LONG_SLEEP_TIME);
        }  
	}
	
	@Override
    public void initialize (IRainbowReportingPort port) throws RainbowConnectionException {
        super.initialize (port);
        initializeConnections ();
    }

    private void initializeConnections () throws RainbowConnectionException {
        // Create a port to subscribe to model changes (if analyzer is event based)
        m_modelChangePort = RainbowPortFactory.createModelChangeBusSubscriptionPort ();
        m_modelChangePort.subscribe (m_plannerFinish, this);

        // Create a port to query things about a model
        m_modelsManagerPort = RainbowPortFactory.createModelsManagerRequirerPort ();

        // Create a port to change a model (e.g., to trigger adaptation, to set predicted score, etc.)
        m_modelUSPort = RainbowPortFactory.createModelsManagerClientUSPort (this);
    }

	@Override
	public void dispose() {
		m_reportingPort.dispose ();
        m_modelUSPort.dispose ();
        m_modelChangePort.dispose ();
	}

	@Override
	public void onEvent(ModelReference mr, IRainbowMessage message) {
		synchronized (this) {
			// Either a new deadline or a new IG has been set (or both)
    		// Timing analyzer can resume periodic analysis
            m_waitForPlanner = false;
        }
	}

	@Override
	public String getProperty(String key) {
		return null;
	}

	@Override
	public void setProperty(String arg0, String arg1) {

	}

	@Override
	public RainbowComponentT getComponentType() {
		return RainbowComponentT.ANALYSIS;
	}

	@Override
	protected void log(String text) {
		m_reportingPort.info (RainbowComponentT.ANALYSIS, text);
	}

	@Override
	protected void runAction() {
		// If adaptation planning is in progress, wait for it to finish before performing analysis
    	if (!m_waitForPlanner) {
	        // Do the periodic analysis on the models of interest
	        updateIGProgress();
	        updateMissionState();
	        updateEnvMap();
	
	        if (m_igProgress != null && m_missionState != null && m_envMap != null) {
	            // The current instruction
	            IInstruction currentInstruction = m_igProgress.getCurrentInstruction();
	
	            // Only performing timing analysis once per instruction
	            if (isNewOrUnpassedInstruction(currentInstruction)) {
	
	                if (m_missionState != null) {
	                    // The remaining instructions, excluding the current instruction
	                    List<IInstruction> remainingInstructions = (List<IInstruction>) m_igProgress.getRemainingInstructions();
	                    
	                    boolean hasEnoughEnergy = hasEnoughEnergy(currentInstruction, remainingInstructions);
	                    
	                    if (hasEnoughEnergy) {
	                    	// Keep track of the latest instruction that we have analyzed the accuracy property,
	                    	// and it passed
		                    m_prevAnalyzedAndPassedInstruction = currentInstruction;
	                    }
	                    
	                    if (hasEnoughEnergy && !m_missionState.isRobotAccurate()) {
	                    	// Previous plan was not accurate; new plan is expected to be accurate
	                    	// Update MissionState model to indicate that the robot can now get close enough to the goal
	                        MissionStateModelInstance missionStateModel = (MissionStateModelInstance) m_modelsManagerPort
	                                .<MissionState> getModelInstance(m_msRef);
	                        SetRobotAccurateCmd robotAccurateCmd = missionStateModel.getCommandFactory().setRobotAccurateCmd(true);
	                        m_modelUSPort.updateModel (robotAccurateCmd);
	                    } else if (!hasEnoughEnergy) {
	                    	// Update MissionState model to indicate that the robot cannot get close enough to the goal
	                        MissionStateModelInstance missionStateModel = (MissionStateModelInstance) m_modelsManagerPort
	                                .<MissionState> getModelInstance(m_msRef);
	                        SetRobotAccurateCmd robotAccurateCmd = missionStateModel.getCommandFactory().setRobotAccurateCmd(false);
	                        m_modelUSPort.updateModel (robotAccurateCmd);
	                        
	                        // Wait for the planner to come up with an adaptation plan
	                        m_waitForPlanner = true;
	                    }
	                }
	            }
	        }
    	}
	}
	
	private void updateIGProgress() {
        InstructionGraphModelInstance igModel = (InstructionGraphModelInstance) m_modelsManagerPort
                .<InstructionGraphProgress> getModelInstance(m_igRef);
        m_igProgress = igModel != null ? igModel.getModelInstance() : null;
    }

    private void updateMissionState() {
        MissionStateModelInstance missionStateModel = (MissionStateModelInstance) m_modelsManagerPort
                .<MissionState> getModelInstance(m_msRef);
        m_missionState = missionStateModel != null ? missionStateModel.getModelInstance() : null;
    }

    private void updateEnvMap() {
        EnvMapModelInstance envMapModel = (EnvMapModelInstance) m_modelsManagerPort.<EnvMap> getModelInstance (m_emRef);
        m_envMap = envMapModel != null ? envMapModel.getModelInstance() : null;
    }
    
    /**
     * Checks if this instruction is different from the previously-analyzed instruction,
     * or if this instruction has not passed the timing check previously
     */
    private boolean isNewOrUnpassedInstruction(IInstruction instruction) {
        return (m_prevAnalyzedAndPassedInstruction == null && instruction != null)
                || (instruction != null && !instruction.equals (m_prevAnalyzedAndPassedInstruction));
    }
    
    /**
     * Checks if the instructions can be completed with the remaining energy
     */
    private boolean hasEnoughEnergy(IInstruction currentInstruction, List<IInstruction> remainingInstructions) {
    	double batteryCharge = m_missionState.getBatteryCharge();
    	double planEnergyConsumption = getExpectedIGEnergyConsumption(currentInstruction, remainingInstructions);
    	return batteryCharge >= planEnergyConsumption;
    }
    
    /**
     * Calculates the expected energy consumption of the current and the remaining instructions
     */
    private double getExpectedIGEnergyConsumption(IInstruction currentInstruction, List<IInstruction> remainingInstructions) {
    	double totalEnergy = 0;
    	
    	// Goal location
    	double goalX = m_envMap.getNodeX(m_missionState.getTargetWaypoint());
		double goalY = m_envMap.getNodeY(m_missionState.getTargetWaypoint());
    	
    	double sourceX = m_missionState.getCurrentPose().getX();
		double sourceY = m_missionState.getCurrentPose().getY();
		double sourceW = m_missionState.getCurrentPose().getRotation();
		
		List<IInstruction> allInstructions = new ArrayList<>();
		allInstructions.add(currentInstruction);
		allInstructions.addAll(remainingInstructions);
		
		int i = 0;
		for (IInstruction instruction : allInstructions) {
			double instEnergy = 0;
			
			// Special case: for the last instruction, only calculate the energy required to get
			// within a certain radius from the goal location
			if (i == allInstructions.size() - 1 && instruction instanceof MoveAbsHInstruction) {
				// For MoveAbsH, use the target location that is GOAL_RADIUS from the goal, and is nearest the source
				Pair<Double, Double> targetLocation = getNearestTargetLocation(sourceX, sourceY, goalX, goalY, GOAL_RADIUS);
				double targetX = targetLocation.firstValue();
				double targetY = targetLocation.secondValue();
				
				MoveAbsHInstruction moveAbsH = (MoveAbsHInstruction) instruction;
				String newMoveAbsH = 
						String.format("MoveAbsH(%d, %d, %d, %d)", targetX, targetY, moveAbsH.getSpeed(), moveAbsH.getTargetW());
				MoveAbsHInstruction moveAbsHCopy = 
						new MoveAbsHInstruction(moveAbsH.getInstructionLabel(), newMoveAbsH, moveAbsH.getNextInstructionLabel());
				instEnergy = getMoveAbsHEnergyConsumption(moveAbsHCopy, sourceX, sourceY, sourceW);
				
			} else if (i == allInstructions.size() - 1 && instruction instanceof ForwardInstruction) {
				// For Forward, use the distance that is GOAL_RADIUS shorter than the original distance
				ForwardInstruction forward = (ForwardInstruction) instruction;
				String newForward = String.format("Forward(%d, %d)", forward.getDistance() - GOAL_RADIUS, forward.getSpeed());
				ForwardInstruction forwardCopy = 
						new ForwardInstruction(forward.getInstructionLabel(), newForward, forward.getNextInstructionLabel());
				instEnergy = getForwardEnergyConsumption(forwardCopy, sourceX, sourceY, sourceW);
				
			} else if (instruction instanceof MoveAbsHInstruction) {
    			MoveAbsHInstruction moveAbsH = (MoveAbsHInstruction) instruction;
    			instEnergy = getMoveAbsHEnergyConsumption(moveAbsH, sourceX, sourceY, sourceW);
    			
    			// Update source pose for the next instruction
    			sourceX = moveAbsH.getTargetX();;
    			sourceY = moveAbsH.getTargetY();
    			sourceW = moveAbsH.getTargetW();
    		} else if (instruction instanceof ForwardInstruction) {
    			ForwardInstruction forward = (ForwardInstruction) instruction;
    			instEnergy = getForwardEnergyConsumption(forward, sourceX, sourceY, sourceW);
    			
    			// Update source pose for the next instruction
    			sourceX = sourceX + forward.getDistance() * Math.cos(sourceW);;
    			sourceY = sourceY + forward.getDistance() * Math.sin(sourceW);
    		} else {
    			//TODO
    		}
    		
    		totalEnergy += instEnergy;
    		i++;
		}
    	
    	return totalEnergy;
    }
    
    /**
     * Finds the nearest (x, y) location to the source location that is a given distance away from the goal.
     */
    private Pair<Double, Double> getNearestTargetLocation(double sourceX, double sourceY, 
    		double goalX, double goalY, double buffer) {
    	double lowerBoundX = goalX - buffer;
		double upperBoundX = goalX + buffer;
		double lowerBoundY = goalY - buffer;
		double upperBoundY = goalY + buffer;
		
    	List<Double> targetXs = new ArrayList<>();
		List<Double> targetYs = new ArrayList<>();
		targetXs.add(goalX);
		targetYs.add(upperBoundY);
		targetXs.add(upperBoundX);
		targetYs.add(goalY);
		targetXs.add(goalX);
		targetYs.add(lowerBoundY);
		targetXs.add(lowerBoundX);
		targetYs.add(goalY);
		
		List<Double> manhattanDistances = new ArrayList<>();
		manhattanDistances.add(getManhattanDistance(sourceX, sourceY, targetXs.get(0), targetXs.get(0)));
		manhattanDistances.add(getManhattanDistance(sourceX, sourceY, targetXs.get(1), targetXs.get(1)));
		manhattanDistances.add(getManhattanDistance(sourceX, sourceY, targetXs.get(2), targetXs.get(2)));
		manhattanDistances.add(getManhattanDistance(sourceX, sourceY, targetXs.get(3), targetXs.get(3)));
		int minIndex = manhattanDistances.indexOf(Collections.min(manhattanDistances));
		double targetX = targetXs.get(minIndex);
		double targetY = targetYs.get(minIndex);
		Pair<Double, Double> targetLocation = new Pair<Double, Double>(targetX, targetY);
		return targetLocation;
    }
    
    private double getManhattanDistance(double sourceX, double sourceY, double targetX, double targetY) {
    	return Math.abs(sourceX - targetX) + Math.abs(sourceY - targetY);
    }
    
    /**
     * Calculates the energy consumption of MoveAbs(x, y, v, w)
     */
    private double getMoveAbsHEnergyConsumption(MoveAbsHInstruction moveAbsH, double sourceX, double sourceY, double sourceW) {
    	double targetX = moveAbsH.getTargetX();
		double targetY = moveAbsH.getTargetY();
		double targetW = moveAbsH.getTargetW();
		double moveSpeed = moveAbsH.getSpeed();
		double rotateSpeed = MapTranslator.ROBOT_ROTATIONAL_SPEED_VALUE;
		double cpuAvgUsage = 0; //TODO
		
		double moveAbsHEnergy = getMovementEnergyConsumption(true, sourceX, sourceY, sourceW, targetX, targetY, targetW, 
				moveSpeed, rotateSpeed, true, cpuAvgUsage);
		return moveAbsHEnergy;
    }
    
    /**
     * Calculates the energy consumption of Forward(d, v)
     */
    private double getForwardEnergyConsumption(ForwardInstruction forward, double sourceX, double sourceY, double sourceW) {
    	double distance = forward.getDistance();
		double speed = forward.getSpeed();
		// Approximate target location
		// Assume that the current location is close to the location where this Forward command was issued
		// TODO: ensure this
		double targetX = sourceX + distance * Math.cos(sourceW);
		double targetY = sourceY + distance * Math.sin(sourceW);
		double cpuAvgUsage = 0; //TODO
		
		double forwardEnergy = getMovementEnergyConsumption(false, sourceX, sourceY, sourceW, targetX, targetY, sourceW, 
				speed, 0, false, cpuAvgUsage);
		return forwardEnergy;
    }
    
    /**
     * Calculates the energy consumption of a movement
     */
    private double getMovementEnergyConsumption(boolean isManhattanDistance,
    		double sourceX, double sourceY, double sourceW,
    		double targetX, double targetY, double targetW,
    		double moveSpeed, double rotateSpeed, boolean kinectEnabled, double cpuAvgUsage) {
    	String moveSpeedStr;
		
		if (moveSpeed == MapTranslator.ROBOT_HALF_SPEED_VALUE) {
			moveSpeedStr = MapTranslator.ROBOT_HALF_SPEED_CONST;
		} else if (moveSpeed == MapTranslator.ROBOT_FULL_SPEED_VALUE) {
			moveSpeedStr = MapTranslator.ROBOT_FULL_SPEED_CONST;
		} else {
			moveSpeedStr = MapTranslator.ROBOT_HALF_SPEED_CONST;
		}
		
		double distance;
		if (isManhattanDistance) {
			// Manhattan distance
			distance = Math.abs(sourceX - targetX) + Math.abs(sourceY - targetY);
		} else {
			// Euclidean distance
			distance = Math.sqrt(Math.pow(sourceX - targetX, 2) + Math.pow(sourceY - targetY, 2));
		}
		
		// Energy consumed by straight move
		double moveTime = distance / moveSpeed;
		double moveEnergy = BatteryPredictor.batteryConsumption(moveSpeedStr, false, kinectEnabled, cpuAvgUsage, moveTime);
		double instEnergy = moveEnergy;
		
		boolean rotating = Heading.convertFromRadians(sourceW) != Heading.convertFromRadians(targetW);
		
		// Energy consumed by rotation, if any
		if (rotating) {
			double rotateTime = Math.abs(sourceW - targetW) / rotateSpeed;
			double rotateEnergy = BatteryPredictor.batteryConsumption("", true, kinectEnabled, cpuAvgUsage, rotateTime);
			instEnergy += rotateEnergy;
		}
		
		return instEnergy;
    }
}
