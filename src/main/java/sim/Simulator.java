package sim;

import java.util.ArrayList;
import java.util.HashMap;

import org.pmw.tinylog.Level;
import org.pmw.tinylog.Logger;

import sim.AgentDistribution.Distribution;
import sim.GraphGeneratorSource.GraphType;

import com.google.common.collect.Range;


/**
 * The "controller" class. Single point of contact for the user.
 *
 */
public class Simulator {


    ///////////////////////////////////////////////////////////////////////////
    // PUBLIC ENUM
    ///////////////////////////////////////////////////////////////////////////


    public enum NodeSelection {

        /**
         * Node selection weighted based on the number of agents in each
         * node. More agents thus means higher probability of selection.
         */
        WEIGHTED,

        /**
         * Node selection is non-weighted, all nodes are equiprobable
         */
        NON_WEIGHTED;
    }


    ///////////////////////////////////////////////////////////////////////////
    // PROTECTED ENUM
    ///////////////////////////////////////////////////////////////////////////


    protected enum ActionSelection {

        /**
         * Actions have weights (probabilities)
         */
        WEIGHTED,

        /**
         * 50/50 action selection, equiprobable
         */
        NON_WEIGHTED;
    }


    ///////////////////////////////////////////////////////////////////////////
    // PRIVATE ATTRIBUTES
    ///////////////////////////////////////////////////////////////////////////


    /**
     * Should SimulatorRun data be saved to disk?
     */
    private boolean flag_saveData = false;


    /**
     * Graph visualization display flag
     */
    private boolean flag_vis = false;


    /**
     * Simulation charts (plots) display flag
     */
    private boolean flag_charts = false;


    /**
     * Automatic graph generation flag
     */
    private boolean flag_generateGraph = false;


    /**
     * Values for terms A & B in the election process completion estimate
     * equation. They are buffer factors. See paper for more.
     *
     * A: multiplicative factor
     * B: additive factor
     */
    private int termA;
    private int termB;


    /**
     * Max number of time-steps the simulation should go on for, after which
     * point we exit
     */
    private int maxTimeSteps;


    /**
     * Range of the number of agents that a simulation runs will have
     */
    private Range<Integer> numAgents;


    /**
     * How many times should the simulation be run?
     */
    private int runs;


    /**
     * Holds all data about entire simulation. Will be exported to JSON at the
     * very end
     */
    private static ArrayList<SimulatorRun> runData = new ArrayList<SimulatorRun>();


    /**
     * What is the probabilities of the possible actions?
     */
    private HashMap<Integer, Double> actionProbability;


    private ExtendedGraph g;
    private TinylogProperties tinylog;
    private GraphVis gv = GraphVis.getInstance();
    private RandomSource rs = RandomSource.getInstance();
    private AgentDistribution dist;
    private SimulatorMetaData smd;
    private ActionSelection as = ActionSelection.NON_WEIGHTED;
    private GraphType gt = GraphType.CUSTOM;
    private double interactProbability = 0.50;
    private double traversalProbability = 0.50;


    ///////////////////////////////////////////////////////////////////////////
    // CONSTRUCTOR
    ///////////////////////////////////////////////////////////////////////////


    /**
     * Create a simulation
     *
     * @param g Graph for the simulation to run on
     * @param numAgents Range of agents to be created
     * @param termA Multiplicative factor
     * @param termB Additive factor
     * @param maxTimeSteps Max number of time steps to be performed. That is
     * 					   interacts + traversals.
     * @param runs How many times should the simulation be run?
     * @param logLevel What log level should the simulation run at?
     */
    public Simulator(ExtendedGraph g, Range<Integer> numAgents,
                                                     int termA,
                                                     int termB,
                                                     int maxTimeSteps,
                                                     int runs,
                                                     Level logLevel) {
        // Init logging before anything else
        tinylog = new TinylogProperties(logLevel);

        this.g = g;
        this.runs = runs;
        this.termA = termA;
        this.termB = termB;
        this.numAgents = numAgents;
        this.maxTimeSteps = maxTimeSteps;

        Logger.info("Simulator CREATED");
    }


    ///////////////////////////////////////////////////////////////////////////
    // PRIVATE METHODS
    ///////////////////////////////////////////////////////////////////////////


    private HashMap<Integer, Range<Double>> actionProbabilitySpread() {
        double offset = 0.0;
        HashMap<Integer, Range<Double>> map = new HashMap<Integer, Range<Double>>();

        for (Integer key : actionProbability.keySet()) {
            // Probability of the action
            double p = actionProbability.get(key);

            // Upper bound for the range of the this node
            double upper = offset + p;

            // https://code.google.com/p/guava-libraries/wiki/RangesExplained
            map.put(key, Range.closedOpen(offset, upper));

            Logger.trace("{0}; Probability {1}; Offset {2}; Upper {3}", key,
                                                                        p,
                                                                        offset,
                                                                        upper);

            offset = upper;
        }

        return map;
    }


    private void execute(int numAgents) {
        g.setNumAgents(numAgents);

        for (int y = 0; y < runs; y++) {
            Logger.info("----------------------------------------------------");
            Logger.info("STARTING RUN: " + (y + 1));


            ///////////////////////////////////////////////////////////////////
            // INIT OPS


            /*
             * Init helper classes
             */
            rs.init(g);

            if (flag_vis) {
                gv.init(g);
            }


            /*
             * Create and distribute the agents
             */
            dist.init(g);
            dist.execute();


            ///////////////////////////////////////////////////////////////////


            TimeStep ts = new TimeStep(g, termA, termB, flag_vis, as);
            for (int i = 0; i < maxTimeSteps; i++) {
                ts.step();

                // Checking for infection complete as well causes problems.
                // With the way the algo is structured, if leader declares
                // election complete before it really happens, infection no
                // longer occurs. Sim then runs till max time step for no reason
                if (ts.isFlag_leaderElectionComplete() &&
                    ts.isFlag_allElectionComplete()) {
                    Logger.info("STEP: {0}; Cutting off simulation - all " +
                                "actions complete", i);
                    break;
                }
            }

            ts.end();
            Logger.info("ENDING RUN: " + (y + 1));
            Logger.info("----------------------------------------------------");
        }
    }


    private void postmortem() {
        // Fill in simulator metadata
        smd = new SimulatorMetaData();

        smd.setDate(tinylog.getDate());
        smd.setDuration(tinylog.getDate().getTime());
        smd.setTermA(termA);
        smd.setTermB(termB);
        smd.setMaxTimeSteps(maxTimeSteps);
        smd.setRuns(runs);
        smd.setInteractProbability(interactProbability);
        smd.setTraversalProbability(traversalProbability);
        smd.setNumNodes(g.getNodeCount());
        smd.setGraphType(gt);
        smd.setNumAgents(numAgents);


        // Rest of stats

        double avg_infection_level = 0.0;
        double leader_error = 0.0;

        // 3 types of data points
        int totalRuns = (numAgents.upperEndpoint() - numAgents.lowerEndpoint()) * runs;
        int maxItems = totalRuns * 3;
        MarkersChart mc = new MarkersChart(maxItems, tinylog.getDirName(),
                                                     tinylog.getTimestamp());


        for (SimulatorRun r : runData) {
            int nAgents = r.getNumAgents();

            avg_infection_level += r.getInfections() / (double) nAgents;

            // If the infection complete step is zero, means it never happened
            int s = r.getInfectionCompleteStep();
            if (s > 0 && s > r.getLeaderElectionCompleteStep()) {
                leader_error++;
            }

            // TODO: interaction or step?
            mc.addDataPoint(nAgents, r.getInfectionCompleteInteractions());
            mc.addDataPointLeader(nAgents, r.getLeaderElectionCompleteInteractions());
            mc.addDataPointAll(nAgents, r.getAllElectionCompleteInteractions());
        }

        // Must come before chart display, exception thrown otherwise
        mc.save();


        if (flag_charts) {
            mc.display();
        }


        smd.setAvgInfectionLevel((avg_infection_level / totalRuns) * 100.0);
        smd.setAvgLeaderError((leader_error / totalRuns) * 100.0);
        smd.setTotalRuns(totalRuns);


        JSONUtil.writeJSON(tinylog.getDirName(), "metadata",
                                                 tinylog.getTimestamp(),
                                                 smd,
                                                 true);

        if (flag_saveData) {
            JSONUtil.writeJSON(tinylog.getDirName(),
                               "data", tinylog.getTimestamp(),
                               runData, false);
        }


        GraphIO.writeGraph(g, tinylog.getDirName(), tinylog.getTimestamp());
    }


    ///////////////////////////////////////////////////////////////////////////
    // PROTECTED METHODS
    ///////////////////////////////////////////////////////////////////////////


    protected static ArrayList<SimulatorRun> getSimData() {
        return runData;
    }


    ///////////////////////////////////////////////////////////////////////////
    // PUBLIC METHODS
    ///////////////////////////////////////////////////////////////////////////


    /**
     * Execute the simulation
     */
    public void execute() {
        Logger.info("Simulation SETTINGS" + toString());

        g.setNullAttributesAreErrors(true);
        dist = new AgentDistribution(flag_vis);

        if (flag_vis) {
            gv.init(g);
            gv.display();
        }

        if (flag_generateGraph) {
            GraphGeneratorSource.getInstance().generateGraph(g);
        }


        /*
         * Check the graph
         */
        if (!g.isConnected()) {
            /*
             * Graph not connected, thus some node(s) not reachable. We can't
             * work with such a graph, as certain agent(s) may not be reachable
             * and so can never be infected
             */
            Logger.error("Graph is NOT connected");
            System.exit(-1);
        }

        if (g.hasDeadEnd()) {
            Logger.warn("The graph has a dead END");
        }

        if (g.getNodeCount() == 1) {
            Logger.warn("Single node graph - no traverse actions allowed");
        }


        if (as == ActionSelection.WEIGHTED) {
            g.setActionProbabilitySpread(actionProbabilitySpread());
        }


        // TODO: range types - closed, closeOpen, etc
        int lower = numAgents.lowerEndpoint();
        int upper = numAgents.upperEndpoint();

        for (int y = lower; y < upper; y++) {
            execute(y);
        }

        Logger.info("ALL SIMULATION RUNS COMPLETE");

        postmortem();
    }


    public String toString() {
        return "\n\t Term A: " + termA +
               ";\n\t Term B: " + termB +
               ";\n\t Max interactions: " + maxTimeSteps;
    }


    ///////////////////////////////////////////////////////////////////////////
    // PUBLIC METHODS - SIMULATION SETTINGS
    ///////////////////////////////////////////////////////////////////////////


    /**
     * How should the agents be distributed across the graph? Set an agent
     * distribution algorithm.
     *
     * @param agentDistributionAlgo Agent distribution algorithm to be used. See
     * 								constants in AgentDistribution class
     */
    public void agentDistribution(Distribution agentDistributionAlgo) {
        g.setAgentDistribution(agentDistributionAlgo);
    }


    /**
     * How should a node randomly be selected? Set node selection method.
     *
     * @param method Node selection method
     */
    public void nodeSelection(NodeSelection method) {
        g.setNodeSelection(method);
    }


    /**
     * Define the probabilities for interaction and traversal actions. By
     * default, this is 50/50. Probabilities are specified as values between 0
     * and 1. Thus, the values passed must sum to 1.
     *
     * @param interaction Probability of interaction action
     * @param traversal Probability of traversal action.
     */
    public void setActionProbabilites(double interaction, double traversal) {
        // TODO: CHECK SUM & IF 50/50, use NON_WEIGHTED

        as = ActionSelection.WEIGHTED;
        actionProbability = new HashMap<Integer, Double>();
        actionProbability.put(TimeStep.ACTION_INTERACT, interaction);
        actionProbability.put(TimeStep.ACTION_TRAVERSE, traversal);

        interactProbability = interaction;
        traversalProbability = traversal;
    }


    /**
     * How long should the simulator sleep between each graph action? This is
     * default 0 ms, however if you would like to slow down the simulation to
     * better view the visualization, then you will want to increase this.
     *
     * @param sleep How many milliseconds the simulation should sleep between
     * 			    each event
     */
    public void visSleep(int sleep) {
        Sleep.SLEEP = sleep;
    }


    /**
     * Turn on graph visualization. This is OFF by default, as large scale
     * testing doesn't require visualization. Must be called before execute()
     */
    public void vis() {
        flag_vis = true;
    }


    /**
     * Turn on charts for viewing at the end of the simulation. This is OFF by
     * default and the chart data that is saved to disk is not affected by this
     * setting.
     */
    public void charts() {
        flag_charts = true;
    }


    /**
     * Redirect logs to standard output
     */
    public void stdout() {
        tinylog.stdout();
    }


    public void saveSimData() {
        flag_saveData = true;
    }


    ///////////////////////////////////////////////////////////////////////////
    // PUBLIC METHODS - GRAPH GENERATORS
    ///////////////////////////////////////////////////////////////////////////


    /**
     * Generate a fully connected graph
     *
     * @param n Number of nodes the graph should have
     * @param directed Should the edges be directed?
     * @param randomlyDirectedEdges Should the edges be randomly directed?
     */
    public void generateGraphFullyConnected(int n,
                                            boolean directed,
                                            boolean randomlyDirectedEdges) {
        gt = GraphType.FULLY_CONNECTED;
        flag_generateGraph = true;
        GraphGeneratorSource.getInstance()
                            .graphFullyConnected(n, directed,
                                                    randomlyDirectedEdges);
    }


    /**
     * Generate a chain graph.
     *
     * If the graph is undirected, nodes are single linked regardless of
     * doublyLinked argument.
     *
     * @param n Number of nodes the graph should have
     * @param directed Should the edges be directed?
     * @param doublyLinked Should the nodes be doubly linked?
     * @param loopBack Should the last node have an edge looping back to the
     *                 start?
     */
    public void generateGraphChain(int n, boolean directed,
                                          boolean doublyLinked,
                                          boolean loopBack) {
        gt = GraphType.CHAIN;
        flag_generateGraph = true;
        GraphGeneratorSource.getInstance()
                            .graphChain(n, directed, doublyLinked, loopBack);
    }


    /**
     * Generate an n * n grid graph
     *
     * @param n How many nodes should this graph have? Since this is a grid,
     *          this equates to n * n nodes
     * @param directed Should the graph be directed?
     * @param crossEdges Should the grid have cross edges?
     */
    public void generateGraphGrid(int n, boolean directed, boolean crossEdges) {
        gt = GraphType.GRID;
        flag_generateGraph = true;
        GraphGeneratorSource.getInstance().graphGrid(n, directed, crossEdges);
    }
}
