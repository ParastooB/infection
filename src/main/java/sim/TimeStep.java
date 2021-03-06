/*
 * TimeStep.java
 * infection
 *
 * Copyright (C) 2014  beltex <https://github.com/beltex>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package sim;

import org.graphstream.graph.Edge;
import org.pmw.tinylog.Logger;

import sim.Simulator.ActionSelection;
import sim.Simulator.NodeSelection;


/**
 * Heart beat of the simulator (time step). That is, handles each
 * iteration of the simulator.
 *
 */
public class TimeStep {


    ///////////////////////////////////////////////////////////////////////////
    // PRIVATE ATTRIBUTES
    ///////////////////////////////////////////////////////////////////////////

    /**
     * Is infection complete?
     */
    private boolean flag_infectionComplete;


    /**
     * Does the leader believe election is complete?
     */
    private boolean flag_leaderElectionComplete;


    /**
     * Do all agents believe election is complete?
     */
    private boolean flag_allElectionComplete;


    /**
     * Have the agents hit a dead end?
     */
    private boolean agentDeadEnd;


    /**
     * The current time step
     */
    private int step;


    private int infectionCounter;
    private int electionCompleteCounter;
    private int actionInteractCounter;
    private int actionTraverseCounter;

    private GraphVis gv;
    private ExtendedGraph g;
    private RandomSource rs;
    private SimulatorRun simRun;

    private final int termA;
    private final int termB;
    private final int numAgents;
    private final int leaderAID;

    private final boolean deadEnd;
    private final boolean flag_vis;
    private final NodeSelection ns;
    private final ActionSelection as;


    ///////////////////////////////////////////////////////////////////////////
    // PROTECTED ATTRIBUTES
    ///////////////////////////////////////////////////////////////////////////


    protected static final int ACTION_INTERACT = 0;
    protected static final int ACTION_TRAVERSE = 1;


    ///////////////////////////////////////////////////////////////////////////
    // CONSTRUCTOR
    ///////////////////////////////////////////////////////////////////////////


    public TimeStep(ExtendedGraph g, int termA, int termB, boolean flag_vis,
                                                           ActionSelection as) {
        this.g = g;
        this.termA = termA;
        this.termB = termB;

        step = 0;
        infectionCounter = 1;
        actionInteractCounter = 0;
        actionTraverseCounter = 0;
        electionCompleteCounter = 0;
        ns = g.getNodeSelection();


        deadEnd = g.getHasDeadEnd();
        agentDeadEnd = false;
        flag_infectionComplete = false;
        flag_leaderElectionComplete = false;
        flag_allElectionComplete = false;


        this.flag_vis = flag_vis;
        this.as = as;
        numAgents = g.getNumAgents();
        leaderAID = numAgents - 1;


        simRun = new SimulatorRun();
        simRun.setNumAgents(numAgents);


        if (flag_vis) {
            gv = GraphVis.getInstance();
        }

        rs = RandomSource.getInstance();

        //simRun.addInfection(step, infectionCounter);
        Logger.debug("TimeStep INIT");
    }


    ///////////////////////////////////////////////////////////////////////////
    // PUBLIC METHODS
    ///////////////////////////////////////////////////////////////////////////


    /**
     * A single step of the simulation (tick or heart beat).
     */
    public void step() {
        Logger.debug("Step: {0} BEGIN", step);

        int action = -1;
        ExtendedNode n = null;

        // If the graph structure has no dead end, no point in checking for one
        if (!deadEnd) {
            // Graph is safe for traverse action

            /*
             * Pick an action
             */

            switch (as) {
                case NON_WEIGHTED:
                    action = rs.nextAction();
                    break;
                case WEIGHTED:
                    action = rs.nextActionWeighted();
                    break;
            }

            /*
             * Pick a random node
             */

            switch (ns) {
                case NON_WEIGHTED:
                    n = rs.nextNode(action);
                    break;
                case WEIGHTED:
                    n = rs.nextNodeWeighted(action);
                    break;
            }
        }
        else if (agentDeadEnd || g.agentDeadEnd()) {
            /*
             * Can only do interact now.
             *
             * TODO: Below code needs to only be set once, but done on every
             *       timestep once true.
             *
             * NOTE: agentDeadEnd flag is used as short-circuit to prevent
             *       unneeded calls to agentDeadEnd() - which is expensive
             */

            agentDeadEnd = true;
            action = ACTION_INTERACT;
            n = g.getNode(g.getDeadEnd_nodeID());
        }

        Logger.debug("ACTION: {0}", action);


        /*
         * Execute the action
         */

        switch (action) {
            case ACTION_INTERACT:
                actionInteract(n);
                break;
            case ACTION_TRAVERSE:
                actionTraverse(n);
                break;
        }

        Logger.debug("Step: {0} COMPLETE", step);
        step++;
    }


    /**
     * Simulation run complete, cleanup
     */
    public void end() {
        Logger.info("Simulation run COMPLETE");
        postmortem();
    }


    ///////////////////////////////////////////////////////////////////////////
    // PUBLIC METHODS - GETTERS
    ///////////////////////////////////////////////////////////////////////////


    public boolean isFlag_infectionComplete() {
        return flag_infectionComplete;
    }


    public boolean isFlag_leaderElectionComplete() {
        return flag_leaderElectionComplete;
    }


    public boolean isFlag_allElectionComplete() {
        return flag_allElectionComplete;
    }


    ///////////////////////////////////////////////////////////////////////////
    // PRIVATE METHODS - ACTIONS
    ///////////////////////////////////////////////////////////////////////////


    /**
     * Two agents interact. That is, two agents are selected at random, from
     * node n.
     *
     * This is the interact action from the original paper.
     *
     * @param n Node to selected two random agents from
     */
    private void actionInteract(ExtendedNode n) {
        // Pick a random pair of agents in this node
        Agent[] agent_pair = rs.nextAgentPair(n);

        // The two randomly selected agents that will interact
        Agent agent_i = agent_pair[0];
        Agent agent_j = agent_pair[1];

        Logger.debug("Agent i - {0}", agent_i);
        Logger.debug("Agent j - {0}", agent_j);


        /*
         * If either agent believes that the election is complete, spread the
         * word to the other
         */
        if (agent_i.isElectionComplete() || agent_j.isElectionComplete()) {
            agent_i.setElectionComplete(true);
            agent_j.setElectionComplete(true);

            // TODO: Infection should occur here as well

            // DO NOT ALLOW NON-REAL INFECTIONS TO INCREMENT THIS COUNTER, OR
            // ANY OF THE OTHER ONES FOR THAT MATTER
            if (!flag_allElectionComplete &&
                (agent_i.getLeaderAID() == leaderAID ||
                 agent_j.getLeaderAID() == leaderAID)) {

                electionCompleteCounter++;
                flag_electionComplete();
            }


            Logger.debug("Election complete from agents: {0}, {1}", agent_i,
                                                                    agent_j);
        }
        else {
            // Compare who the agents believe is the leader
            int diff = agent_i.getLeaderAID() - agent_j.getLeaderAID();

            if (diff > 0) {
                // Agent i infects agent j
                agentInfection(agent_i, agent_j);
                possibleLeader(agent_i);
            }
            else if (diff < 0) {
                // Agent j infects agent i
                agentInfection(agent_j, agent_i);
                possibleLeader(agent_j);
            }
            else {
                Logger.debug("Tie, both infected by the same agent");

                metFollower(agent_i);
                metFollower(agent_j);

                /*
                 * Key change in logic from the original paper. Without this
                 * check here as well, the leader NEVER determines that the
                 * election is complete
                 */
                isElectionComplete(agent_i);
                isElectionComplete(agent_j);
            }
        }
        actionInteractCounter++;


        // Update the view
        if (flag_vis) {
            gv.updateNode(n.getId());
            gv.updateInfectionLabel();
            gv.updateAllElectionCompleteLabel();
        }
    }


    /**
     * One agent traverses an edge. That is, a randomly selected agent within
     * node n traverses (travels along) an outgoing edge (of this node).
     *
     * This is the newly added action from the original paper, as result of
     * performing the simulation on a graph.
     *
     * @param n Node that will have an agent leave from it
     */
    private void actionTraverse(ExtendedNode n) {
        // Pick a random agent in the current node
        // Remove it from the current Node
        Agent agent = n.removeAgent(rs.nextAgentIndex(n));

        // Pick a random out going edge
        Edge e = rs.nextLeavingEdge(n);

        // Get the outgoing node
        ExtendedNode outGoingNode = e.getOpposite(n);

        // Add agent to this node
        outGoingNode.addAgent(agent);

        actionTraverseCounter++;


        // Update the view
        if (flag_vis) {
            gv.updateNode(n.getId());
            gv.updateEdge(e, true);
            gv.updateNode(outGoingNode.getId());
            gv.updateEdge(e, false);
        }

        Logger.debug("Agent traversed!");
    }


    ///////////////////////////////////////////////////////////////////////////
    // PRIVATE METHODS - ALGO LOGIC
    ///////////////////////////////////////////////////////////////////////////


    private void agentInfection(Agent infector, Agent infected) {
        int infectorLeaderAID = infector.getLeaderAID();
        infected.setLeaderAID(infectorLeaderAID);

        if (infectorLeaderAID == leaderAID) {
            infectionCounter++;
            //simRun.addInfection(step, infectionCounter);

            // Is infection complete?
            if (!flag_infectionComplete && infectionCounter == numAgents) {
                Logger.info("STEP: {0}; All agents INFECTED", step);
                simRun.setInfectionCompleteStep(step);
                simRun.setInfectionCompleteInteractions(actionInteractCounter);

                flag_infectionComplete = true;
            }
        }
    }


    private boolean possibleLeader(Agent agent) {
        // TODO: Explain meaning of boolean return
        // Check if the agent interacted with anyone with a higher AID
        if (agent.getLeaderAID() == agent.getAID()) {
            agent.converted();
            Logger.debug("Possible leader: {0}", agent);

            isElectionComplete(agent);

            return true;
        }

        return false;
    }


    private boolean isElectionComplete(Agent agent) {
        if ((termB + (termA * agent.getConversions())) < agent.getMetFollowers()) {
            agent.setLeader(true);
            agent.setElectionComplete(true);

            Logger.info("STEP: {0}; Agent believes election is complete and " +
                        "is the leader " +
                        "\n\t # of interactions: {1}; " +
                        "{2}",
                        step, actionInteractCounter, agent);

            // Is this the real leader that believes election is complete?
            if (agent.getAID() == leaderAID) {
                simRun.setLeaderElectionCompleteStep(step);
                simRun.setLeaderElectionCompleteInteractions(actionInteractCounter);

                electionCompleteCounter++;
                flag_leaderElectionComplete = true;

                // Update the view
                if (flag_vis) {
                    gv.updateLeaderElectionCompleteLabel(true);
                    gv.updateAllElectionCompleteLabel();
                }

                // TODO: Is this needed here?
                flag_electionComplete();

                if (!flag_infectionComplete) {
                    Logger.warn("Leader delcared election complete EARLY");
                }
            }

            return true;
        }

        return false;
    }


    private boolean metFollower(Agent agent) {
        if (agent.getLeaderAID() == agent.getAID()) {
            agent.metFollower();
            Logger.debug("Agent met a follwer: {0}", agent);
            return true;
        }

        return false;
    }


    ///////////////////////////////////////////////////////////////////////////
    // PRIVATE METHODS - HELPERS
    ///////////////////////////////////////////////////////////////////////////


    private void flag_electionComplete() {
        // Do all agents believe election is complete?
        if (!flag_allElectionComplete && electionCompleteCounter == numAgents) {

            Logger.info("STEP: {0}; All agents believe election is complete", step);
            simRun.setAllElectionCompleteStep(step);
            simRun.setAllElectionCompleteInteractions(actionInteractCounter);

            flag_allElectionComplete = true;
        }
    }


    private void postmortem() {
        Logger.info("Simulation run POSTMORTEM - BEGIN");

        // Set completed simulation run stats
        simRun.setInfections(infectionCounter);
        simRun.setElectionCompleteCount(electionCompleteCounter);
        simRun.setInteractions(actionInteractCounter);
        simRun.setTraversals(actionTraverseCounter);

        // Add it to the list of all runs
        Simulator.getSimData().add(simRun);

        /*
         * Log stats
         */
        Logger.info("# of INFECTED agents: " + infectionCounter + "/" + numAgents);
        Logger.info("# of agents that believe election is COMPLETE: " + electionCompleteCounter + "/" + numAgents);
        Logger.info("# of agent INTERACTIONS: " + actionInteractCounter);
        Logger.info("# of agent TRAVERSALS: " + actionTraverseCounter);
        Logger.info("MARKER - Infection Complete Step: " + simRun.getInfectionCompleteStep());
        Logger.info("MARKER - Leader Election Complete Step: " + simRun.getLeaderElectionCompleteStep());
        Logger.info("MARKER - All Election Complete Step: " + simRun.getAllElectionCompleteStep());
        Logger.info("MARKER - Infection Complete INTERACTIONS: " + simRun.getInfectionCompleteInteractions());
        Logger.info("MARKER - Leader Election Complete INTERACTIONS: " + simRun.getLeaderElectionCompleteInteractions());
        Logger.info("MARKER - All Election Complete INTERACTIONS: " + simRun.getAllElectionCompleteInteractions());

        Logger.info("Simulation POSTMORTEM - COMPLETE");
    }
}
